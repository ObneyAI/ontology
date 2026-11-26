(ns lint-grain-logic
  (:require [clojure.string :as str]
            [rewrite-clj.zip :as z]))


(defn runtime-namespace? [provider]
  (boolean (re-find #"^ai\.obney\.grain\.(?:command-processor|query-processor|read-model-processor|event-store|todo-processor)(?:-[^.]+)?(?:\.|$)" (str provider))))

(defn forbidden? [sym]
  (and (symbol? sym) (runtime-namespace? (namespace sym))
       (not= sym 'ai.obney.grain.event-store-v3.interface/->event)))

(defn- nodes [root]
  (take-while (complement z/end?) (iterate z/next root)))

(defn- value [loc]
  (when loc (try (z/sexpr loc) (catch Exception _ nil))))

(defn- siblings [loc]
  (take-while some? (iterate z/right loc)))

(defn- children [loc]
  (siblings (z/down loc)))

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

(defn- libspecs [form prefix]
  (cond
    (symbol? form) [[(symbol (str prefix form))]]
    (and (sequential? form) (symbol? (first form)))
    (if (and (next form) (every? sequential? (rest form)))
      (mapcat #(libspecs % (str prefix (first form) ".")) (rest form))
      [(into [(symbol (str prefix (first form)))] (rest form))])
    :else []))

(defn namespace-info [root]
  (let [all (remove data? (nodes root))
        specs (mapcat (fn [clause]
                        (mapcat #(libspecs % "") (rest (value clause))))
                      (filter #(and (= :list (z/tag %)) (in-ns? %)
                                    (contains? #{:require :require-macros :use}
                                               (value (z/down %)))) all))
        own (set (keep #(when (and (= :list (z/tag %))
                                  (contains? #{'defn 'defn- 'def 'defonce 'defmulti} (value (z/down %))))
                         (value (z/right (z/down %)))) all))]
    (reduce (fn [info [provider & options]]
              (let [{:keys [as as-alias refer rename]} (apply hash-map options)]
                (-> info
                    (update :aliases #(cond-> % (or as as-alias) (update (or as as-alias) (fnil conj #{}) provider)))
                    (update :wildcards #(cond-> % (and (= :all refer) (runtime-namespace? provider)) (conj provider)))
                    (update :refers #(merge-with into %
                                                 (into {} (for [s (if (sequential? refer) refer [])]
                                                            [(get rename s s) #{(symbol (str provider) (str s))}])))))))
            {:aliases {} :refers {} :own own :wildcards #{}} specs)))

(defn resolved-symbols [info sym]
  (when (symbol? sym)
    (if-let [qualifier (namespace sym)]
      (set (map #(symbol (str %) (name sym)) (get (:aliases info) (symbol qualifier) #{qualifier})))
      (when-not (contains? (:own info) sym) (get (:refers info) sym)))))

(defn- binding-names [form]
  (cond
    (symbol? form) (if (= '& form) #{} #{(symbol (name form))})
    (vector? form) (into #{} (mapcat binding-names) form)
    (map? form) (into #{} (mapcat (fn [[k v]]
                                  (cond
                                    (and (keyword? k) (contains? #{"keys" "syms" "strs"} (name k))) (map #(symbol (name %)) v)
                                    (= :as k) (binding-names v)
                                    (keyword? k) []
                                    :else (binding-names k)))) form)
    :else #{}))

(defn- before? [a b]
  (neg? (compare (z/position a) (z/position b))))

(defn- vector-bindings [bindings loc]
  (loop [pairs (partition-all 2 (children bindings)) out #{}]
    (if-let [[binding init] (first pairs)]
      (if (some #(= (z/position binding) (z/position %)) (take-while some? (iterate z/up loc)))
        (into out (binding-names (value binding)))
        (if (before? binding loc)
        (cond
          (= :let (value binding)) (recur (rest pairs) (into out (vector-bindings init loc)))
          (keyword? (value binding)) (recur (rest pairs) out)
          (and init (or (not (before? init loc))
                        (some #(= (z/position init) (z/position %)) (take-while some? (iterate z/up loc))))) out
          :else (recur (rest pairs) (into out (binding-names (value binding)))))
        out))
      out)))

(defn- shadowed? [loc sym]
  (when (and (symbol? sym) (nil? (namespace sym))
             (not (some #(or (= :var (z/tag %))
                             (and (= :list (z/tag %)) (contains? #{'var 'clojure.core/var} (value (z/down %)))))
                        (take-while some? (iterate z/up loc)))))
    (some
     (fn [ancestor]
       (when (= :list (z/tag ancestor))
         (let [parts (vec (children ancestor)) head (value (first parts))
               op (when (symbol? head) (name head))
               binding (second parts)
               argvec (first (filter #(= :vector (z/tag %)) parts))
               parent-head (value (some-> ancestor z/up z/down))
               letfn-method? (and (= :vector (some-> ancestor z/up z/tag))
                                   (contains? #{'letfn 'clojure.core/letfn}
                                              (value (some-> ancestor z/up z/up z/down))))]
           (cond
             (contains? #{"if-let" "if-some"} op)
             (and (not (some #(= (some-> (nth parts 3 nil) z/position) (z/position %))
                             (take-while some? (iterate z/up loc))))
                  (contains? (vector-bindings binding loc) sym))
             (contains? #{"let" "let*" "loop" "loop*" "binding" "with-open" "with-local-vars" "for" "doseq" "dotimes" "when-let" "when-some" "when-first"} op)
             (contains? (vector-bindings binding loc) sym)
             (= "as->" op)
             (and (= sym (value (nth parts 2 nil)))
                  (not (before? loc (nth parts 2))))
             (= "letfn" op)
             (contains? (set (keep #(value (z/down %)) (children binding))) sym)
             (or letfn-method? (contains? #{"fn" "fn*" "defn" "defn-" "defmacro" "defmethod"} op)
                 (and (= :vector (some-> parts first z/tag))
                      (or (contains? #{'fn 'fn* 'defn 'defn- 'defmacro} parent-head)
                          (= :vector (some-> ancestor z/up z/tag)))))
             (or (contains? (binding-names (value argvec)) sym)
                 (and (contains? #{"fn" "fn*"} op) (= sym (value binding))))
             (= "catch" op) (= sym (value (nth parts 2 nil)))
             :else false))))
     (take-while some? (iterate z/up loc)))))

(defn runtime-references [file source]
  (try
    (binding [*read-eval* false]
      (let [root (z/of-string source {:track-position? true}) info (namespace-info root)]
        (when (some #(= :eval (z/tag %)) (remove data? (nodes root)))
          (throw (ex-info "Reader evaluation is not supported" {})))
        (when (seq (:wildcards info))
          (throw (ex-info "Use explicit refers instead of :refer :all for Grain runtime namespaces" {})))
        (vec (for [loc (nodes root)
                   :when (and (= :token (z/tag loc)) (not (data? loc)) (not (in-ns? loc)))
                   :let [sym (value loc)]
                   :when (not (shadowed? loc sym))
                   target (filter forbidden? (resolved-symbols info sym))
                   :let [[row col] (z/position loc)]]
               {:file file :row row :col col :symbol target
                :message (str "Grain runtime reference " target " is banned in logic files; move orchestration to its canonical Grain handler file")}))))
    (catch Exception error
      [{:file file :row 1 :col 1 :kind :parse-error :message (str "Cannot lint source: " (ex-message error))}])))

(defn logic-file? [file]
  (and (str/starts-with? file "src/ai/obney/ontology/core/")
       (not (contains? #{"commands" "operations" "projection" "state" "schemas" "api_schemas"}
                       (second (re-find #"/([^/]+)\.clj[cs]?$" file))))))

(defn lint-source [file source]
  (when (logic-file? file) (runtime-references file source)))
