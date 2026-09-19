(require '[clojure.test :as test])
(def suites '[ai.obney.orc.ontology.core-test
              ai.obney.orc.ontology.ontology-id-scoping-test
              ai.obney.orc.ontology.integration-verification-test
              ai.obney.orc.ontology.custom-ontology-lifecycle-test
              ai.obney.orc.ontology.contribution-integration-test
              ai.obney.orc.ontology.embedding-contribution-integration-test
              ai.obney.orc.ontology.evolutionary-builder-test
              ai.obney.orc.ontology.evolutionary-tenant-isolation-test
              ai.obney.orc.ontology.evolutionary-resolution-regression-test
              ai.obney.orc.ontology.tree-class-hierarchy-test
              ai.obney.orc.ontology.r05a-behavioral-subtree-foundation-test
              ai.obney.orc.ontology.r05c-minting-affordance-test
              ai.obney.orc.ontology.r05d-consolidator-behavioral-inference-test
              ai.obney.orc.ontology.r07-investigation-behavioral-seed-test
              ai.obney.orc.ontology.cc1-claim-store-test
              ai.obney.orc.ontology.el4-harvest-test])
(doseq [suite suites] (require suite))
(let [result (apply test/run-tests suites)]
  (System/exit (+ (:fail result) (:error result))))
