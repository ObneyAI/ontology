(require '[clojure.java.io :as io]
         '[clojure.pprint :as pp]
         '[ai.obney.ontology.interface]
         '[ai.obney.ontology.profile])

(let [entries
      (->> (all-ns)
           (mapcat (comp vals ns-interns))
           (keep (fn [v]
                   (when (.hasRoot ^clojure.lang.Var v)
                     (let [x (.getRawRoot ^clojure.lang.Var v)
                           kind (cond
                                  (instance? clojure.lang.Atom x) :atom
                                  (instance? clojure.lang.Ref x) :ref
                                  (instance? clojure.lang.Agent x) :agent
                                  (instance? clojure.lang.Volatile x) :volatile
                                  (instance? clojure.lang.Delay x) :delay
                                  (instance? clojure.lang.MultiFn x) :multimethod
                                  (instance? java.util.WeakHashMap x) :weak-map
                                  (instance? java.util.concurrent.Executor x) :executor
                                  (instance? ThreadLocal x) :thread-local
                                  (:dynamic (meta v)) :dynamic-var)]
                       (when kind
                         {:var (str (ns-name (:ns (meta v))) "/" (:name (meta v)))
                          :kind kind :class (some-> x class .getName)
                          :file (:file (meta v)) :line (:line (meta v))})))))
           (sort-by :var) vec)]
  (io/make-parents "evidence/global-state-vars.edn")
  (spit "evidence/global-state-vars.edn" (with-out-str (pp/pprint entries)))
  (println "Recorded" (count entries) "mutable/dynamic/delayed/dispatch roots."))
(shutdown-agents)
