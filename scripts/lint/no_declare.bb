(ns lint-no-declare
  (:require [rewrite-clj.zip :as z]))


(defn- nodes [loc]
  (take-while (complement z/end?) (iterate z/next loc)))

(defn- value [loc]
  (when loc (try (z/sexpr loc) (catch Exception _ nil))))

(defn- data? [loc]
  (loop [ancestor loc unquotes 0]
    (when ancestor
      (let [tag (z/tag ancestor)]
        (cond
          (or (contains? #{:quote :uneval} tag)
              (and (= :list tag)
                   (contains? #{'quote 'clojure.core/quote} (value (z/down ancestor))))) true
          (contains? #{:unquote :unquote-splicing} tag) (recur (z/up ancestor) (inc unquotes))
          (= :syntax-quote tag) (if (pos? unquotes) (recur (z/up ancestor) (dec unquotes)) true)
          :else (recur (z/up ancestor) unquotes))))))

(defn- in-ns? [loc]
  (some #(and (= :list (z/tag %)) (= 'ns (value (z/down %))))
        (take-while some? (iterate z/up loc))))

(defn- unquoted [form]
  (if (and (seq? form) (contains? #{'quote 'clojure.core/quote} (first form)))
    (second form) form))

(defn- core-references [[provider & options]]
  (when (contains? #{'clojure.core 'cljs.core} provider)
    (let [{:keys [as as-alias rename]} (apply hash-map options)
          rename (unquoted rename)]
      (cond-> []
        (or as as-alias) (conj (symbol (str (or as as-alias)) "declare"))
        (get rename 'declare) (conj (get rename 'declare))))))

(defn- libspec-references [form prefix]
  (cond
    (and (sequential? form) (symbol? (first form)))
    (let [[provider & options] form
          provider (if prefix (symbol (str prefix "." provider)) provider)]
      (if (sequential? (first options))
        (mapcat #(libspec-references % provider) options)
        (core-references (cons provider options))))
    :else []))

(defn- references [root]
  (into #{'declare 'clojure.core/declare 'cljs.core/declare}
        (mapcat
         (fn [loc]
           (cond
             (and (= :vector (z/tag loc))
                  (some #(and (= :list (z/tag %))
                              (contains? #{:require :require-macros :use
                                           'require 'clojure.core/require 'cljs.core/require
                                           'use 'clojure.core/use} (value (z/down %))))
                        (take-while some? (iterate z/up (z/up loc)))))
             (libspec-references (value loc) nil)
             (= :list (z/tag loc))
             (let [[head & options] (value loc)]
               (cond
                 (and (in-ns? loc) (contains? #{:require :require-macros :use} head))
                 (mapcat #(libspec-references % nil) options)
                 (and (in-ns? loc) (= :refer-clojure head))
                 (when-let [renamed (get-in (apply hash-map options) [:rename 'declare])]
                   [renamed])
                 (contains? #{'require 'clojure.core/require 'cljs.core/require
                              'use 'clojure.core/use} head)
                 (mapcat #(libspec-references (unquoted %) nil) options)
                 (contains? #{'refer 'clojure.core/refer 'cljs.core/refer} head)
                 (core-references (cons (unquoted (first options)) (rest options)))
                 (and (contains? #{'alias 'clojure.core/alias 'cljs.core/alias} head)
                      (contains? #{'clojure.core 'cljs.core} (unquoted (second options))))
                 [(symbol (str (unquoted (first options))) "declare")]))))
         (remove data? (nodes root)))))

(defn lint-source [file source]
  (try
    (binding [*read-eval* false]
      (let [root (z/of-string source {:track-position? true})
            banned (references root)]
        (vec
         (for [loc (nodes root)
               :when (and (= :token (z/tag loc))
                          (contains? banned (value loc))
                          (not (data? loc)) (not (in-ns? loc)))
               :let [[row col] (z/position loc)]]
           {:file file :row row :col col
            :message "declare is banned; define dependencies before use, or use self-recursion, local letfn or explicit function arguments"}))))
    (catch Exception error
      [{:file file :row 1 :col 1 :message (str "Cannot lint source: " (ex-message error))}])))
