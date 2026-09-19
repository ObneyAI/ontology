(require '[clojure.test :as test]
         '[ai.obney.ontology.boundary-test :as boundary]
         '[ai.obney.ontology.mutation-test :as mutations]
         '[ai.obney.ontology.rdf-test :as rdf-tests]
         '[ai.obney.ontology.search-test :as search-tests]
         '[ai.obney.ontology.core.commands :as commands]
         '[ai.obney.ontology.core.state :as state]
         '[ai.obney.ontology.core.rdf :as rdf]
         '[ai.obney.ontology.algorithms :as algorithms])

(defn run-isolated [test-var replacements]
  (binding [test/*report-counters* (ref test/*initial-report-counters*)
            test/*test-out* (java.io.StringWriter.)]
    (with-redefs-fn replacements #(test/test-vars [test-var]))
    @test/*report-counters*))

(let [command-result commands/command-result
      mutants [{:name :skip-graph-validation :test #'boundary/manual-hierarchy-maintains-inverses-and-rejects-cycles
                :replace {#'commands/validate-graph! (fn [s & _] s)}}
               {:name :drop-relationship-views :test #'boundary/declared-inverse-produces-reverse-predicate
                :replace {#'state/relationships (fn [_ _] [])}}
               {:name :ignore-request-payload :test #'mutations/every-mutation-retries-original-result-after-graph-advances
                :replace {#'commands/digest (constantly "constant")}}
               {:name :ignore-rdf-validity :test #'rdf-tests/raw-triple-contributions-are-validated-before-commit
                :replace {#'rdf/validate-triples! identity}}
               {:name :compare-invalid-vectors :test #'search-tests/unusable-vectors-do-not-participate-in-ranking
                :replace {#'algorithms/cosine-similarity (fn [& _] 1.0)}}
               {:name :remove-append-cas :test #'boundary/competing-contributions-check-revision-at-append
                :replace {#'commands/command-result (fn [& args] (dissoc (apply command-result args) :command-result/cas))}}]
      results (mapv (fn [{:keys [name test replace]}]
                      (let [control (run-isolated test {}) mutant (run-isolated test replace)]
                        {:mutation name :control control :mutant mutant
                         :detected (and (zero? (+ (:fail control) (:error control))) (pos? (:fail mutant)))})) mutants)]
  (doseq [result results] (prn result))
  (prn {:detected (count (filter :detected results)) :total (count results)})
  (shutdown-agents)
  (System/exit (if (every? :detected results) 0 1)))
