(ns lint-no-sql-delete
  (:require [babashka.fs :as fs]
            [clojure.string :as str]
            [rewrite-clj.zip :as z]))

(def clojure-extensions #{"clj" "cljc" "cljs" "bb" "edn"})
(def source-extensions (into clojure-extensions #{"sql" "sh" "bash" "zsh" "py" "js" "jsx" "ts" "tsx"}))
(def deletion-pattern #"(?i)\bdelete\s+from\b")
(def failure-message "SQL DELETE statements are banned; preserve event history and use the owning graph commands")

(defn- literal-string [loc]
  (case (z/tag loc)
    (:token :multi-line) (let [v (z/sexpr loc)] (when (string? v) v))
    :list (let [head (z/down loc)]
            (when (and head (= :token (z/tag head))
                       (contains? #{'str 'clojure.core/str} (z/sexpr head)))
              (let [parts (mapv literal-string (take-while some? (iterate z/right (z/right head))))]
                (when (every? string? parts) (apply str parts)))))
    nil))

(defn- mask-sql-comments [text]
  (let [length (count text) result (StringBuilder.)]
    (loop [i 0 depth 0 line? false]
      (if (= i length)
        (str result)
        (let [ch (.charAt ^String text i)
              pair (subs text i (min length (+ i 2)))
              newline? (contains? #{\newline \return} ch)]
          (cond
            line? (do (.append result (if newline? ch \space))
                      (recur (inc i) depth (not newline?)))
            (= pair "/*") (do (.append result "  ") (recur (+ i 2) (inc depth) false))
            (and (pos? depth) (= pair "*/"))
            (do (.append result "  ") (recur (+ i 2) (dec depth) false))
            (pos? depth) (do (.append result (if newline? ch \space))
                             (recur (inc i) depth false))
            (= pair "--") (do (.append result "  ") (recur (+ i 2) 0 true))
            :else (do (.append result ch) (recur (inc i) 0 false))))))))

(defn- text-findings [file text row col]
  (let [matcher (re-matcher deletion-pattern (mask-sql-comments text))]
    (loop [findings []]
      (if (.find matcher)
        (let [prefix (subs text 0 (.start matcher))
              lines (str/split prefix #"\n" -1)
              line-offset (dec (count lines))]
          (recur (conj findings {:file file :row (+ row line-offset)
                                :col (+ (if (zero? line-offset) col 1) (count (last lines)))
                                :message failure-message})))
        findings))))

(defn lint-source [file source]
  (try
    (if (contains? clojure-extensions (fs/extension file))
      (binding [*read-eval* false]
        (let [root (z/of-string source {:track-position? true})]
          (loop [loc root findings []]
            (if (z/end? loc)
              findings
              (let [text (literal-string loc)
                    [row col] (z/position loc)
                    hits (when text (map #(assoc % :row row :col col)
                                         (text-findings file text row col)))]
                (recur (z/next loc)
                       (into findings hits)))))))
      (text-findings file source 1 1))
    (catch Exception error
      [{:file file :row 1 :col 1 :message (str "Cannot lint source: " (ex-message error))}])))
