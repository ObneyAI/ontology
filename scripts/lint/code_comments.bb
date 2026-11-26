(ns lint-code-comments
  (:require [clojure.string :as str]
            [rewrite-clj.zip :as z]))

(def max-title-length 60)

(defn nodes [loc]
  (take-while (complement z/end?) (iterate z/next* loc)))

(defn value [loc]
  (when loc (try (z/sexpr loc) (catch Exception _ nil))))

(defn- data? [loc]
  (loop [loc loc unquotes 0]
    (when loc
      (let [tag (z/tag loc)]
        (cond
          (or (contains? #{:quote :uneval} tag)
              (and (= :list tag)
                   (contains? #{'quote 'clojure.core/quote} (value (z/down loc))))) true
          (contains? #{:unquote :unquote-splicing} tag) (recur (z/up loc) (inc unquotes))
          (= :syntax-quote tag) (if (pos? unquotes) (recur (z/up loc) (dec unquotes)) true)
          :else (recur (z/up loc) unquotes))))))

(defn- unquoted [form]
  (if (and (seq? form) (contains? #{'quote 'clojure.core/quote} (first form)))
    (second form) form))

(defn- imported-comments [spec prefix]
  (when (and (sequential? spec) (symbol? (first spec)))
    (let [[provider & options] spec
          provider (if prefix (symbol (str prefix "." provider)) provider)]
      (if (sequential? (first options))
        (mapcat #(imported-comments % provider) options)
        (when (contains? #{'clojure.core 'cljs.core} provider)
          (let [{:keys [as as-alias rename]} (apply hash-map options)]
            (cond-> []
              (or as as-alias) (conj (symbol (str (or as as-alias)) "comment"))
              (get rename 'comment) (conj (get rename 'comment)))))))))

(defn- comment-names [root]
  (into #{'comment 'clojure.core/comment 'cljs.core/comment}
        (mapcat
         (fn [loc]
           (let [head (value (z/down loc))]
             (cond
               (contains? #{:require :require-macros 'require 'clojure.core/require} head)
               (mapcat #(imported-comments (unquoted %) nil) (rest (value loc)))
               (= :refer-clojure head)
               (when-let [renamed (get-in (apply hash-map (rest (value loc))) [:rename 'comment])]
                 [renamed])
               (contains? #{'alias 'clojure.core/alias} head)
               (let [[_ alias provider] (value loc)]
                 (when (contains? #{'clojure.core 'cljs.core} (unquoted provider))
                   [(symbol (str (unquoted alias)) "comment")])))))
         (filter #(and (= :list (z/tag %)) (not (data? %))) (nodes root)))))

(defn comment-form? [loc names]
  (and (= :list (z/tag loc))
       (contains? names (value (z/down loc)))
       (not (data? loc))))

(defn- title? [title]
  (and (string? title) (= title (str/trim title))
       (<= 1 (count title) max-title-length)
       (boolean (re-matches #"[\p{L}\p{N}][\p{L}\p{N} &/_-]*" title))))

(defn- comment-text [loc]
  (str/replace (z/string loc) #"\r?\n$" ""))

(defn allowed-comment-rows [root source]
  (let [lines (str/split-lines source)
        comments (filter #(and (= :comment (z/tag %))
                               (let [[row col] (z/position %)]
                                 (str/blank? (subs (nth lines (dec row)) 0 (dec col)))))
                         (nodes root))
        by-row (into {} (map (fn [loc] [(first (z/position loc)) (comment-text loc)])) comments)
        columns (into {} (map (fn [loc] (z/position loc))) comments)
        headers (mapcat
                   (fn [[row text]]
                     (let [[_ _ title] (re-matches #";; (-{3,}|={3,}) (.+) \1" text)
                           border? (re-matches #";; (?:-{3,}|={3,})" text)
                           middle (get by-row (inc row))
                           middle-title (when middle (second (re-matches #";; (.+)" middle)))]
                       (cond
                         (title? title) [row]
                         (and border? (= text (get by-row (+ row 2))) (title? middle-title)
                              (= (get columns row) (get columns (inc row)) (get columns (+ row 2))))
                         [row (inc row) (+ row 2)])))
                   by-row)
        interpreter (when (and (= 1 (get columns 1))
                               (re-matches #"#!/usr/bin/env (?:bb|clojure)" (get by-row 1 ""))) [1])]
    (set (concat headers interpreter))))

(defn lint-source [file source]
  (try
    (binding [*read-eval* false]
      (let [root (z/of-string* source {:track-position? true})
            allowed (allowed-comment-rows root source)
            names (comment-names root)]
        (vec
         (for [loc (nodes root)
               :let [[row col] (z/position loc)
                     message (cond
                               (and (= :comment (z/tag loc)) (not (contains? allowed row)))
                               "Code comments are banned; use a standalone framed section header"
                               (= :uneval (z/tag loc))
                               "Reader discards (#_) are banned"
                               (comment-form? loc names)
                               "Comment forms are banned")]
               :when message]
           {:file file :row row :col col :message message}))))
    (catch Exception error
      [{:file file :row 1 :col 1 :message (str "Cannot lint source: " (ex-message error))}])))
