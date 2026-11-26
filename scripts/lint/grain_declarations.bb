(ns lint-grain-declarations
  (:require [clojure.string :as str]
            [rewrite-clj.zip :as z]))

(def primitive-files
  {"defevent" "events.clj"
   "defcommand" "commands.clj"
   "defquery" "queries.clj"
   "defreadmodel" "state.clj"
   "defschemas" "schemas.clj"
   "defprocessor" "processors.clj"
   "defeventmodel" "event_model.clj"
   "defperiodic" "periodic_tasks.clj"})

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
                   (contains? #{'quote 'clojure.core/quote}
                              (value (z/down ancestor))))) true
          (contains? #{:unquote :unquote-splicing} tag) (recur (z/up ancestor) (inc unquotes))
          (= :syntax-quote tag) (if (pos? unquotes) (recur (z/up ancestor) (dec unquotes)) true)
          :else (recur (z/up ancestor) unquotes))))))

(defn- renamed-primitives [root]
  (into {}
        (for [ns-form (nodes root)
              :when (and (= :list (z/tag ns-form)) (not (data? ns-form))
                         (= 'ns (value (z/down ns-form))))
              loc (nodes ns-form)
              :when (= :vector (z/tag loc))
              :let [[provider & options] (value loc)]
              :when (and (symbol? provider) (str/starts-with? (str provider) "ai.obney.grain."))
              :let [renames (:rename (apply hash-map options))]
              [original renamed] renames
              :when (and (symbol? original) (symbol? renamed)
                         (contains? primitive-files (name original)))]
          [renamed (name original)])))

(defn- declaration [loc renames]
  (when (and (= :list (z/tag loc)) (not (data? loc)))
    (let [head (value (z/down loc))]
      (when (symbol? head)
        (or (get renames head)
            (when (contains? primitive-files (name head)) (name head)))))))

(defn- finding [file loc message]
  (let [[row col] (when loc (z/position loc))]
    {:file file :row (or row 1) :col (or col 1) :message message}))

(defn lint-source [file source]
  (try
    (binding [*read-eval* false]
      (let [root (z/of-string source {:track-position? true})
            renames (renamed-primitives root)
            canonical #(str "src/ai/obney/ontology/core/" %)
            basename (str/replace (last (str/split file #"/")) #"\.clj[cs]$" ".clj")]
        (cond-> (vec (for [loc (nodes root)
                             :let [primitive (declaration loc renames)]
                             :when primitive
                             :let [expected (canonical (primitive-files primitive))]
                             :when (not= file expected)]
                         (finding file loc (str primitive " must be declared in " expected))))
            (and (str/starts-with? file "src/")
                 (some #{basename} (vals primitive-files))
                 (not= file (canonical basename)))
            (conj (finding file nil (str "Use the canonical file " (canonical basename)))))))
    (catch Exception error
      [(finding file nil (str "Cannot lint source: " (ex-message error)))])))
