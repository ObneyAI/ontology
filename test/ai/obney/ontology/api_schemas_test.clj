(ns ai.obney.ontology.api-schemas-test
  (:require [clojure.test :refer [deftest is]]
            [malli.core :as m]
            [malli.instrument :as mi]
            [ai.obney.ontology.interface :as o]
            [ai.obney.ontology.core.api-schemas :as schemas]
            [ai.obney.ontology.support :as h]))

(defn checked [name & arguments]
  (let [v (ns-resolve 'ai.obney.ontology.interface name)]
    (apply (m/-instrument {:schema (:malli/schema (meta v))} @v) arguments)))

(deftest every-public-function-exposes-a-collectable-schema
  (let [functions (into {} (filter (fn [[_ v]] (fn? @v)))
                        (ns-publics 'ai.obney.ontology.interface))]
    (is (= (set (map (comp symbol name) (keys schemas/functions))) (set (keys functions))))
    (doseq [[function-name v] functions]
      (let [schema (:malli/schema (meta v))
            registered-key (keyword "ai.obney.ontology.core.api-schemas" (name function-name))]
        (is (some? (m/function-schema schema)))
        (is (= (m/form schema) (m/form (m/deref registered-key))))))
    (mi/collect! {:ns 'ai.obney.ontology.interface})
    (is (= (set (keys functions))
           (set (keys (get (m/function-schemas) 'ai.obney.ontology.interface)))))
    (is (m/validate ::schemas/graph-event-types o/graph-event-types))))

(deftest public-schemas-cover-real-command-and-read-results
  (h/with-context [ctx]
    (let [oid (:ontology-id (h/ok! (checked 'create-ontology! ctx {:name "Schema fixture"})))
          parent (h/ok! (checked 'create-concept! ctx oid
                         (assoc (h/concept "urn:parent") :embedding [1.0 0.0] :embedding-model "fixture")))
          child (h/ok! (checked 'create-concept! ctx oid (h/concept "urn:child")))
          request {:changes {:broader ["urn:parent"] :scope :updated
                             :alt-labels ["Alias"] :provenance {:source "manual"}
                             :entity-type "Concept"} :request-id (random-uuid)
                   :expected-revision (o/get-revision ctx oid)}
          updated (h/ok! (checked 'update-concept! ctx oid (:concept-id child) request))]
      (is (= updated (checked 'update-concept! ctx oid (:concept-id child) request)))
      (h/ok! (checked 'register-predicate! ctx oid {:predicate "app:peer" :policy {:symmetric? true}}))
      (h/ok! (checked 'create-relationship! ctx {:source-ontology-id oid :source-uri "urn:child"
                                               :target-ontology-id oid :target-uri "urn:parent" :predicate "app:peer"}))
      (h/ok! (checked 'import-contribution! ctx oid
               (assoc (h/request ctx oid [(assoc (h/concept "urn:alias") :label "urn:parent")])
                      :resolution-policy {:match-labels? true}
                      :tbox {:classes [{:uri "urn:class" :label "Class"}]}
                      :abox [{:uri "urn:individual" :type "urn:class" :properties {"urn:p" "value"}}])))
      (h/ok! (checked 'import-rdf! ctx oid
               (assoc (dissoc (h/request ctx oid []) :concepts)
                      :content "<urn:rdf> <http://www.w3.org/2000/01/rdf-schema#label> \"Label\"@en .")))
      (doseq [name ['get-ontology 'get-revision 'get-relationships 'get-contributions
                    'get-sources 'get-canonical-mappings]]
        (is (some? (h/ok! (checked name ctx oid)))))
      (is (true? (checked 'ontology-exists? ctx oid)))
      (is (= 1 (count (checked 'list-ontologies ctx))))
      (is (= (checked 'get-concept-by-uri ctx oid "urn:parent")
             (checked 'get-concept ctx oid (:concept-id parent))))
      (is (= (:concept-id parent) (:concept-id (checked 'get-concept-by-uri ctx oid "urn:parent"))))
      (is (= (checked 'get-concepts ctx) (checked 'get-concepts ctx {:ontology-id oid})))
      (is (= (checked 'get-concept-map ctx) (checked 'get-concept-map ctx {:ontology-ids [oid]})))
      (is (map? (h/ok! (checked 'export-snapshot ctx oid))))
      (is (= 1 (:revision (h/ok! (checked 'export-snapshot ctx oid 1)))))
      (is (= (checked 'export-snapshot ctx oid) (checked 'export-snapshot ctx oid nil)))
      (is (seq (checked 'search ctx oid {:query-text "parent" :embedding-model "fixture"
                                        :embed-query (constantly [1.0 0.0])}))))))

(deftest public-schemas-cover-empty-missing-and-anomaly-results
  (h/with-context [ctx]
    (let [oid (h/graph ctx) missing (random-uuid)]
      (doseq [name ['get-ontology 'get-revision]]
        (is (nil? (checked name ctx missing))))
      (is (false? (checked 'ontology-exists? ctx missing)))
      (is (nil? (checked 'get-concept ctx oid (random-uuid))))
      (is (nil? (checked 'get-concept ctx missing (random-uuid))))
      (is (nil? (checked 'get-concept-by-uri ctx oid "urn:absent")))
      (doseq [name ['get-relationships 'get-contributions 'get-sources 'get-canonical-mappings 'export-snapshot]]
        (is (h/anomaly? (checked name ctx missing))))
      (is (= [] (checked 'get-concepts ctx {:ontology-ids []})))
      (is (= {} (checked 'get-concept-map ctx {:ontology-ids []})))
      (is (= [] (checked 'search ctx oid {})))
      (is (h/anomaly? (checked 'search ctx missing {})))
      (is (h/anomaly? (checked 'get-concepts ctx {:ontology-id missing})))
      (is (h/anomaly? (checked 'get-concept-map ctx {:ontology-id missing})))
      (is (h/anomaly? (checked 'create-concept! ctx missing (h/concept "urn:new"))))
      (is (h/anomaly? (checked 'update-concept! ctx oid (random-uuid) {:changes {:label "Missing"}})))
      (is (h/anomaly? (checked 'import-rdf! ctx oid
                       (assoc (dissoc (h/request ctx oid []) :concepts) :content "malformed")))))))

(deftest request-schemas-reject-invalid-shapes-without-changing-runtime-errors
  (doseq [[schema invalid] [[::schemas/context {:event-store {} :tenant-id (random-uuid)}]
                             [::schemas/ontology-request {:name " "}]
                             [::schemas/concept-request {:uri "urn:a" :label "A"}]
                             [::schemas/update-request {:changes {}}]
                             [::schemas/update-request {:changes {:uri "urn:immutable"}}]
                             [::schemas/filters {:ontology-id (random-uuid) :ontology-ids []}]
                             [::schemas/filters {:ontology-id nil}]
                             [::schemas/filters {:ontology-ids "bad"}]
                             [::schemas/search-request {:limit 0}]
                             [::schemas/search-request {:max-depth -1}]
                             [::schemas/search-request {:weights {:graph ##NaN}}]
                             [::schemas/predicate-request {:predicate "app:p" :policy {:unsupported true}}]]]
    (is (false? (m/validate schema invalid))))
  (h/with-context [ctx]
    (let [oid (h/graph ctx) request (h/request ctx oid [])]
      (is (m/validate ::schemas/contribution-request (dissoc request :expected-revision)))
      (is (not (m/validate ::schemas/contribution-request (assoc request :request-id (random-uuid)))))
      (is (m/validate ::schemas/context ctx))
      (is (m/validate ::schemas/ontology-id (str oid)))
      (is (m/validate ::schemas/update-request {:changes {:embedding nil :embedding-model nil}}))
      (doseq [invalid [{:embedding 42} {:embedding-model {:wrong "shape"}}]]
        (is (not (m/validate ::schemas/concept-request (merge (h/concept "urn:bad") invalid)))))
      (is (not (m/validate ::schemas/local-edge {:subject "a" :predicate "p" :object "b" :metadata 12})))
      (is (m/validate ::schemas/concept-request (assoc (h/concept "urn:opaque") :embedding [##NaN "bad"])))
      (is (= :cognitect.anomalies/incorrect
             (:cognitect.anomalies/category (o/search ctx oid {:limit 0})))))))
