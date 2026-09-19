(ns ai.obney.ontology.resolution-test
  (:require [clojure.test :refer [deftest is]]
            [ai.obney.ontology.interface :as o]
            [ai.obney.ontology.core.state :as state]
            [ai.obney.ontology.support :as h]))

(deftest aliases-normalization-types-and-thresholds
  (doseq [[old incoming threshold expected]
          [[{:label "  Priority_Queue "} {:label "priority-queue"} 0.9 true]
           [{:label "Tree"} {:label "Forest" :alt-labels ["Tree"]} 0.9 true]
           [{:label "Tree" :entity-type "Animal"} {:label "Tree" :entity-type "Plant"} 0.9 false]
           [{:label "Tree" :embedding [1.0 0.0]} {:label "Forest" :embedding [0.8 0.6]} 0.79 true]
           [{:label "Tree" :embedding [1.0 0.0]} {:label "Forest" :embedding [0.8 0.6]} 0.81 false]
           [{:label "Tree" :embedding [1.0e300 1.0e300]} {:label "Forest" :embedding [1.0e300 1.0e300]} 0.99 true]
           [{:label "Tree" :embedding [1.0e-300 1.0e-300]} {:label "Forest" :embedding [1.0e-300 1.0e-300]} 0.99 true]]]
    (h/with-context [ctx]
      (let [oid (h/graph ctx)
            original (h/ok! (o/create-concept! ctx oid (merge (h/concept "urn:original") {:scope :plant :embedding-model "model"} old)))
            candidate (merge (h/concept "urn:candidate") {:scope :plant :embedding-model "model"} incoming)
            result (h/ok! (o/import-contribution! ctx oid (assoc (h/request ctx oid [candidate]) :resolution-policy {:match-labels? true :similarity-threshold threshold})))
            records (o/get-contributions ctx oid)]
        (is (= (if expected 1 2) (count (o/get-concepts ctx {:ontology-id oid}))) (pr-str [old incoming threshold]))
        (is (= (:concept-id original) (:concept-id (o/get-concept-by-uri ctx oid "urn:original"))))
        (is (= expected (boolean (seq (mapcat #(get-in % [:resolution :matches]) records)))))
        (is (every? #(and (string? (:source1-uri %)) (string? (:source2-uri %)) (#{"exact" "semantic"} (:match-type %)))
                    (mapcat #(get-in % [:resolution :matches]) records)))
        (is (every? #(and (Double/isFinite (double (:similarity-score %))) (<= 0 (:similarity-score %) 1))
                    (mapcat #(get-in % [:resolution :matches]) records)))))))

(deftest canonical-selection-is-stable-for-permuted-candidates
  (let [candidates [(assoc (h/concept "urn:alias:a") :label "Alias A" :alt-labels ["Tree"])
                    (assoc (h/concept "urn:alias:b") :label "Alias B" :alt-labels ["Tree"])]
        snapshots (for [input [candidates (vec (reverse candidates))]]
                    (h/with-context [ctx]
                      (let [oid (h/graph ctx)]
                        (h/ok! (o/create-concept! ctx oid (assoc (h/concept "urn:tree") :label "Tree")))
                        (h/ok! (o/import-contribution! ctx oid (assoc (h/request ctx oid input) :resolution-policy {:match-labels? true})))
                        (select-keys (o/export-snapshot ctx oid) [:ttl-output :checksum :triple-count]))))]
    (is (apply = snapshots))))

(deftest same-source-entities-remain-distinct-and-resolution-is-opt-in
  (doseq [policy [{} {:match-labels? true}]]
    (h/with-context [ctx]
      (let [oid (h/graph ctx)]
        (h/ok! (o/import-contribution! ctx oid
                 (assoc (h/request ctx oid [(assoc (h/concept "urn:a") :label "Same")
                                           (assoc (h/concept "urn:b") :label "Same")]) :resolution-policy policy)))
        (is (= #{"urn:a" "urn:b"} (set (map :uri (o/get-concepts ctx {:ontology-id oid})))))))))

(deftest source-identity-is-qualified-across-tenants-and-graphs
  (h/with-context [ctx]
    (let [foreign (assoc ctx :tenant-id (random-uuid))
          ids [[ctx (h/graph ctx)] [ctx (h/graph ctx)] [foreign (h/graph foreign)]]
          source {:source-id (random-uuid) :content-hash "identical" :producer {:evidence {:opaque "not-resolved"}}}
          key (random-uuid)]
      (doseq [[c oid] ids]
        (h/ok! (o/import-contribution! c oid (assoc (h/request c oid [(h/concept "urn:same")]) :source source :contribution-id key)))
        (is (= 1 (count (o/get-sources c oid))))
        (is (= 1 (count (o/get-contributions c oid))))
        (is (= source (:source (first (o/get-contributions c oid)))))))))

(deftest imported-entities-retain-creation-and-update-times
  (h/with-context [ctx]
    (let [oid (h/graph ctx)]
      (h/ok! (o/import-contribution! ctx oid (h/request ctx oid [(h/concept "urn:a")])))
      (let [first-concept (o/get-concept-by-uri ctx oid "urn:a")
            source (first (o/get-sources ctx oid))]
        (is (string? (:created-at first-concept)))
        (is (string? (:registered-at source)))
        (h/ok! (o/import-contribution! ctx oid (h/request ctx oid [(assoc (h/concept "urn:a") :label "Changed")])))
        (let [updated (o/get-concept-by-uri ctx oid "urn:a")]
          (is (= (:created-at first-concept) (:created-at updated)))
          (is (string? (:updated-at updated))))))))

(deftest imported-qualified-endpoints-are-not-silently-retargeted
  (h/with-context [ctx]
    (let [oid (h/graph ctx) other (h/graph ctx)]
      (h/add! ctx oid "urn:a") (h/add! ctx oid "urn:b") (h/add! ctx other "urn:b")
      (let [before (h/capture ctx oid)
            request (assoc (h/request ctx oid []) :relationships
                           [{:source-ontology-id oid :target-ontology-id other
                             :source-uri "urn:a" :target-uri "urn:b" :predicate "skos:related"}])]
        (is (h/anomaly? (o/import-contribution! ctx oid request)))
        (is (= before (h/capture ctx oid)))))))

(deftest invalid-resolution-thresholds-are-atomic
  (h/with-context [ctx]
    (let [oid (h/graph ctx)]
      (doseq [threshold [-0.1 1.1 ##NaN ##Inf "invalid"]]
        (let [before (h/capture ctx oid)
              request (assoc (h/request ctx oid []) :resolution-policy {:match-labels? true :similarity-threshold threshold})]
          (is (h/anomaly? (o/import-contribution! ctx oid request)))
          (is (= before (h/capture ctx oid)))))) ))

(deftest duplicate-uri-candidate-order-is-deterministic
  (let [records [(assoc (h/concept "urn:duplicate") :label "First")
                 (assoc (h/concept "urn:duplicate") :label "Second")]
        outputs (mapv (fn [input]
                        (h/with-context [ctx]
                          (let [oid (h/graph ctx)]
                            (h/ok! (o/import-contribution! ctx oid (h/request ctx oid input)))
                            (:ttl-output (o/export-snapshot ctx oid))))) [records (vec (reverse records))])]
    (is (apply = outputs))))

(deftest contribution-records-retain-digest-result-and-source-time
  (h/with-context [ctx]
    (let [oid (h/graph ctx) first-input (h/request ctx oid [(h/concept "urn:first")])
          result (h/ok! (o/import-contribution! ctx oid first-input))
          record (first (o/get-contributions ctx oid))
          registered (first (o/get-sources ctx oid))]
      (is (= [oid (:contribution-id first-input) (:source first-input) result]
             ((juxt :ontology-id :contribution-id :source :result) record)))
      (is (= #{:contribution-id :ontology-id :source :input :canonical-map
               :resolution :result :recorded-at}
             (set (keys record))))
      (is (re-matches #"[0-9a-f]{64}"
                     (get-in (state/history-state ctx oid)
                             [:contributions [oid (:contribution-id first-input)] :request-hash])))
      (is (= result (o/import-contribution! ctx oid first-input)))
      (is (= 2 (:revision result)))
      (h/ok! (o/import-contribution! ctx oid (assoc (h/request ctx oid []) :source (:source first-input))))
      (is (= [registered] (o/get-sources ctx oid)))
      (is (= 2 (count (o/get-contributions ctx oid)))))))
