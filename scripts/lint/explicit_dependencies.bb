(ns lint-explicit-dependencies
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
  (when (= 'clojure.core provider)
    (let [{:keys [as as-alias rename]} (apply hash-map options)]
      (cond-> []
        (or as as-alias) (conj (symbol (str (or as as-alias)) "requiring-resolve"))
        (get rename 'requiring-resolve) (conj (get rename 'requiring-resolve))))))

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
  (into #{'requiring-resolve 'clojure.core/requiring-resolve}
        (mapcat
         (fn [loc]
           (when (= :list (z/tag loc))
             (let [[head & options] (value loc)]
               (cond
                 (and (in-ns? loc) (= :require head))
                 (mapcat #(libspec-references % nil) options)
                 (and (in-ns? loc) (= :refer-clojure head))
                 (when-let [renamed (get-in (apply hash-map options) [:rename 'requiring-resolve])]
                   [renamed])
                 (contains? #{'require 'clojure.core/require} head)
                 (mapcat #(libspec-references (unquoted %) nil) options)
                 (and (contains? #{'alias 'clojure.core/alias} head)
                      (= 'clojure.core (unquoted (second options))))
                 [(symbol (str (unquoted (first options))) "requiring-resolve")]))))
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
            :message "requiring-resolve is banned; use explicit namespace dependencies and direct calls or Var references"}))))
    (catch Exception error
      [{:file file :row 1 :col 1 :message (str "Cannot lint source: " (ex-message error))}])))
