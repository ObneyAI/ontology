(ns ai.obney.ontology.mutation-test
  (:require [clojure.test :refer [deftest is testing]]
            [ai.obney.ontology.interface :as o]
            [ai.obney.ontology.support :as h]))

(deftest lifecycle-fields-and-query-filters
  (h/with-context [ctx]
    (let [id (:ontology-id (h/ok! (o/create-ontology! ctx {:name "Full" :scope "botany" :description "D" :base-uri "urn:botany:"})))
          other (h/graph ctx)
          parent (h/add! ctx id "urn:p")
          child (h/ok! (o/create-concept! ctx id (assoc (h/concept "urn:c") :scope :child :broader ["urn:p"] :indicators ["green"]
                                                        :provenance {:actor "caller"} :attributes {:opaque [1 2]})))
          foreign (h/add! ctx other "urn:c")
          ont (o/get-ontology ctx id) c (o/get-concept-by-uri ctx id "urn:c")]
      (is (= {:name "Full" :scope "botany" :description "D" :base-uri "urn:botany:" :revision 3}
             (select-keys ont [:name :scope :description :base-uri :revision])))
      (is (string? (:created-at ont)))
      (is (o/ontology-exists? ctx id))
      (is (not (o/ontology-exists? ctx (random-uuid))))
      (is (= #{id other} (set (map :ontology-id (o/list-ontologies ctx)))))
      (is (= (:concept-id child) (:concept-id c)))
      (is (not= (:concept-id child) (:concept-id foreign)))
      (is (= {:actor "caller"} (:provenance c)))
      (is (= {:opaque [1 2]} (:attributes c)))
      (is (= ["green"] (:indicators c)))
      (is (string? (:created-at c)))
      (is (nil? (:updated-at c)))
      (is (nil? (:embedding c)))
      (is (= 3 (count (o/get-concept-map ctx))))
      (is (= 3 (count (o/get-concepts ctx {:ontology-ids [id other]}))))
      (is (= ["urn:c"] (mapv :uri (o/get-concepts ctx {:ontology-id id :scope :child :broader-uri "urn:p"}))))
      (is (empty? (o/get-concepts ctx {:ontology-ids []})))
      (h/ok! (o/update-concept! ctx id (:concept-id child) {:changes {:label "Changed" :description "Updated" :indicators [] :attributes {:v 2}}}))
      (let [updated (o/get-concept-by-uri ctx id "urn:c")]
        (is (= ["Changed" "Updated" [] {:v 2}] ((juxt :label :description :indicators :attributes) updated)))
        (is (= (:created-at c) (:created-at updated)))
        (is (string? (:updated-at updated)))
        (is (= #{"urn:p"} (:broader updated)))))))

(deftest every-mutation-retries-original-result-after-graph-advances
  (doseq [kind [:ontology :concept :update :relationship :predicate]]
    (h/with-context [ctx]
      (let [oid (h/graph ctx) a (h/add! ctx oid "urn:a") _ (h/add! ctx oid "urn:b")
            id (random-uuid)
            [name params changed] (case kind
              :ontology [:ontology/create-ontology {:name "Stable"} {:name "Different"}]
              :concept [:ontology/create-concept (assoc (h/concept "urn:new") :ontology-id oid) {:label "Different"}]
              :update [:ontology/update-concept {:ontology-id oid :concept-id (:concept-id a) :changes {:label "Stable"}} {:changes {:label "Different"}}]
              :relationship [:ontology/create-relationship {:source-ontology-id oid :target-ontology-id oid :source-uri "urn:a" :target-uri "urn:b" :predicate "skos:related"} {:predicate "skos:broader"}]
              :predicate [:ontology/register-predicate {:ontology-id oid :predicate "app:edge" :policy {:symmetric? true}} {:policy {:symmetric? false}}])
            result (h/ok! (h/send! ctx name params id))]
        (when-let [identity-key ({:ontology :ontology-id :concept :concept-id :relationship :relationship-id} kind)]
          (is (uuid? (get result identity-key)))
          (is (not= id (get result identity-key))))
        (h/add! ctx (if (= kind :ontology) (:ontology-id result) oid) "urn:later")
        (let [before (h/graph-events ctx)]
          (is (= result (h/send! ctx name params id)) (str kind))
          (is (= :cognitect.anomalies/conflict (:cognitect.anomalies/category (h/send! ctx name (merge params changed) id))))
          (is (= before (h/graph-events ctx))))))))

(deftest invalid-manual-mutations-are-atomic
  (h/with-context [ctx]
    (let [oid (h/graph ctx) c (h/add! ctx oid "urn:a")]
      (doseq [f [(fn [] (o/create-ontology! ctx {:name " "}))
                (fn [] (o/create-concept! ctx oid (h/concept "urn:a")))
                (fn [] (o/create-concept! ctx oid (assoc (h/concept "urn:new") :label "")))
                (fn [] (o/create-concept! ctx oid (assoc (h/concept "urn:new") :description " ")))
                (fn [] (o/create-concept! ctx oid (assoc (h/concept "urn:new") :uri "")))
                (fn [] (o/create-concept! ctx oid (assoc (h/concept "urn:new") :broader ["urn:absent"])))
                (fn [] (o/create-concept! ctx oid (assoc (h/concept "urn:new") :broader ["urn:new"])))
                (fn [] (o/update-concept! ctx oid (random-uuid) {:changes {:label "Absent"}}))
                (fn [] (o/update-concept! ctx oid (:concept-id c) {:changes {}}))
                (fn [] (o/update-concept! ctx oid (:concept-id c) {:changes {:label ""}}))
                (fn [] (o/update-concept! ctx oid (:concept-id c) {:changes {:description " "}}))
                (fn [] (o/update-concept! ctx oid (:concept-id c) {:changes {:broader ["urn:absent"]}}))
                (fn [] (o/update-concept! ctx oid (:concept-id c) {:changes {:broader ["urn:a"]}}))
                (fn [] (o/update-concept! ctx oid (:concept-id c) {:changes {:uri "urn:renamed"}}))
                (fn [] (o/update-concept! ctx oid (:concept-id c) {:changes {:broader "not-a-vector"}}))]]
        (let [before (h/capture ctx oid)]
          (is (h/rejected? f))
          (is (= before (h/capture ctx oid))))))))

(deftest predicates-register-and-redefinition-is-atomic
  (h/with-context [ctx]
    (let [oid (h/graph ctx) policy {:hierarchical? true :inverse "app:child"}]
      (h/add! ctx oid "urn:a") (h/add! ctx oid "urn:b")
      (is (nil? (get-in (o/get-ontology ctx oid) [:predicate-policies "skos:related" :inverse])))
      (let [before (o/get-revision ctx oid) result (h/ok! (o/register-predicate! ctx oid {:predicate "app:parent" :policy policy}))]
        (is (= (inc before) (:revision result)))
        (is (= policy (get-in (o/get-ontology ctx oid) [:predicate-policies "app:parent"]))))
      (doseq [[p v] [["app:parent" {}] [" " {}] ["app:bad" {:symmetric? "yes"}] ["app:bad" {:unknown true}]]]
        (let [before (h/capture ctx oid)] (is (h/anomaly? (o/register-predicate! ctx oid {:predicate p :policy v}))) (is (= before (h/capture ctx oid)))))
      (h/ok! (o/create-relationship! ctx {:source-ontology-id oid :target-ontology-id oid :source-uri "urn:b" :target-uri "urn:a" :predicate "app:parent"}))
      (is (= #{"app:parent" "app:child"} (set (map :predicate (o/get-relationships ctx oid)))))
      (let [before (h/capture ctx oid)]
        (is (h/anomaly? (o/create-relationship! ctx {:source-ontology-id oid :target-ontology-id oid :source-uri "urn:a" :target-uri "urn:b" :predicate "app:parent"})))
        (is (= before (h/capture ctx oid)))))))

(deftest symmetric-and-inverse-edges-cannot-be-added-twice
  (doseq [[p inverse policy] [["skos:related" "skos:related" nil]
                             ["app:parent" "app:child" {:inverse "app:child"}]]]
    (h/with-context [ctx]
      (let [oid (h/graph ctx)]
        (doseq [uri ["urn:a" "urn:b"]] (h/add! ctx oid uri))
        (when policy (h/ok! (o/register-predicate! ctx oid {:predicate p :policy policy})) (h/ok! (o/register-predicate! ctx oid {:predicate inverse :policy {:inverse p}})))
        (h/ok! (o/create-relationship! ctx {:source-ontology-id oid :target-ontology-id oid :source-uri "urn:a" :target-uri "urn:b" :predicate p}))
        (let [before (h/capture ctx oid)]
          (is (h/anomaly? (o/create-relationship! ctx {:source-ontology-id oid :target-ontology-id oid :source-uri "urn:b" :target-uri "urn:a" :predicate inverse})))
          (is (= before (h/capture ctx oid)))
          (is (= 2 (count (o/get-relationships ctx oid)))))))))

(deftest cross-ontology-links-and-replay-retain-qualified-identities
  (h/with-context [ctx]
    (let [a (h/graph ctx) b (h/graph ctx)]
      (h/add! ctx a "urn:shared") (h/add! ctx b "urn:shared")
      (h/ok! (o/register-predicate! ctx a {:predicate "app:link" :policy {:cross-ontology? true :symmetric? true}}))
      (let [ra (o/get-revision ctx a) rb (o/get-revision ctx b)]
        (h/ok! (o/create-relationship! ctx {:source-ontology-id a :target-ontology-id b :source-uri "urn:shared" :target-uri "urn:shared" :predicate "app:link" :metadata {:source-reference "opaque"}}))
        (is (= (inc ra) (o/get-revision ctx a)))
        (is (= (inc rb) (o/get-revision ctx b))))
      (is (= #{[a b] [b a]} (set (map (juxt :source-ontology-id :target-ontology-id) (o/get-relationships ctx a)))))
      (is (every? #(= {:source-reference "opaque"} (:metadata %)) (o/get-relationships ctx b)))
      (h/with-context [fresh]
        (h/replay! ctx fresh)
        (doseq [id [a b]] (is (= (o/export-snapshot ctx id) (o/export-snapshot fresh id))))))))

(deftest required-identities-never-broaden-queries
  (h/with-context [ctx]
    (let [oid (h/graph ctx)]
      (h/add! ctx oid "urn:secret")
      (is (h/rejected? #(o/get-concept-by-uri ctx nil "urn:secret")))
      (doseq [f [#(o/get-ontology % oid) #(o/list-ontologies %) #(o/get-concepts %)
                 #(o/get-concept-map %) #(o/get-relationships % oid) #(o/get-sources % oid)
                 #(o/get-contributions % oid) #(o/get-canonical-mappings % oid)
                 #(o/search % oid {:query-text "secret"}) #(o/export-snapshot % oid)]]
        (is (h/rejected? #(f (dissoc ctx :tenant-id)))))
      (let [other (assoc ctx :tenant-id (random-uuid))]
        (is (nil? (o/get-concept-by-uri other oid "urn:secret")))
        (is (= :cognitect.anomalies/not-found (:cognitect.anomalies/category (o/get-sources other oid))))
        (is (= :cognitect.anomalies/not-found (:cognitect.anomalies/category (o/get-contributions other oid))))
        (is (= :cognitect.anomalies/not-found (:cognitect.anomalies/category (o/get-canonical-mappings other oid))))
        (is (h/rejected? #(o/export-snapshot other oid)))))))

(deftest relationship-fields-and-imported-inverse-deduplication
  (h/with-context [ctx]
    (let [oid (h/graph ctx)]
      (h/add! ctx oid "urn:a") (h/add! ctx oid "urn:b")
      (h/ok! (o/import-contribution! ctx oid
               (assoc (h/request ctx oid []) :relationships
                      [{:subject "urn:a" :predicate "skos:related" :object "urn:b"}
                       {:subject "urn:b" :predicate "skos:related" :object "urn:a"}])))
      (let [edges (o/get-relationships ctx oid)]
        (is (= 2 (count edges)))
        (is (every? #(nil? (:metadata %)) edges))
        (is (every? #(string? (:created-at %)) edges))))))

(deftest typed-mutation-fields-are-validated
  (h/with-context [ctx]
    (let [oid (h/graph ctx) c (h/add! ctx oid "urn:a")]
      (doseq [changes [{:indicators 42} {:broader {"urn:a" true}} {:label nil}
                       {:attributes "not-a-map"} {:description [:invalid]}
                       {:scope 42} {:alt-labels "alias"} {:alt-labels [42]}
                       {:provenance []} {:entity-type :invalid}]]
        (let [before (h/capture ctx oid)]
          (is (h/anomaly? (o/update-concept! ctx oid (:concept-id c) {:changes changes})))
          (is (= before (h/capture ctx oid))))))))

(deftest payload-cannot-change-the-invoked-operation
  (h/with-context [ctx]
    (let [oid (h/graph ctx)
          result (o/create-concept! ctx oid
                   (assoc (h/concept "urn:expected") :command/name :ontology/create-ontology :name "Unexpected"))]
      (is (not (h/anomaly? result)))
      (is (uuid? (:concept-id result)))
      (is (some? (o/get-concept-by-uri ctx oid "urn:expected")))
      (is (= 1 (count (o/list-ontologies ctx)))))))

(deftest every-relationship-precondition-is-enforced
  (h/with-context [ctx]
    (let [oid (h/graph ctx) other (h/graph ctx)
          alien (assoc ctx :tenant-id (random-uuid)) alien-id (h/graph alien)
          edge {:source-ontology-id oid :target-ontology-id oid :source-uri "urn:a" :target-uri "urn:b" :predicate "skos:broader"}]
      (doseq [u ["urn:a" "urn:b"]] (h/add! ctx oid u))
      (h/add! ctx other "urn:b") (h/add! alien alien-id "urn:b")
      (h/ok! (o/create-relationship! ctx edge))
      (doseq [bad [edge (assoc edge :target-uri "urn:a") (assoc edge :predicate "app:missing")
                   (assoc edge :source-uri "urn:missing") (assoc edge :target-uri "urn:missing")
                   (assoc edge :source-uri "urn:b" :target-uri "urn:a")
                   (assoc edge :target-ontology-id other) (assoc edge :target-ontology-id alien-id)]]
        (let [before (h/capture ctx oid)]
          (is (h/anomaly? (o/create-relationship! ctx bad)) (pr-str bad))
          (is (= before (h/capture ctx oid))))))))

(deftest every-mutation-requires-tenant-context
  (h/with-context [ctx]
    (let [oid (h/graph ctx) c (h/add! ctx oid "urn:a") missing (dissoc ctx :tenant-id)
          before (h/capture ctx oid)]
      (doseq [f [#(o/create-ontology! missing {:name "Forbidden"})
                 #(o/create-concept! missing oid (h/concept "urn:new"))
                 #(o/update-concept! missing oid (:concept-id c) {:changes {:label "Forbidden"}})
                 #(o/create-relationship! missing {:source-ontology-id oid :target-ontology-id oid :source-uri "urn:a" :target-uri "urn:a" :predicate "skos:related"})
                 #(o/register-predicate! missing oid {:predicate "app:forbidden" :policy {}})
                 #(o/import-contribution! missing oid (h/request ctx oid []))]]
        (is (h/rejected? f))
        (is (= before (h/capture ctx oid)))))))

(deftest optional-fields-and-independent-attribute-embedding-updates
  (h/with-context [ctx]
    (let [oid (:ontology-id (h/ok! (o/create-ontology! ctx {:name "Minimal"})))
          c (:concept-id (h/ok! (o/create-concept! ctx oid {:uri "urn:min" :label "Minimal" :description "D"})))
          other (h/graph ctx)]
      (is (= [nil nil nil] ((juxt :description :base-uri :scope) (o/get-ontology ctx oid))))
      (is (= [nil nil nil nil nil nil] ((juxt :embedding :embedding-model :provenance :scope :attributes :indicators) (o/get-concept-by-uri ctx oid "urn:min"))))
      (doseq [change [{:attributes {:opaque [1 2]}} {:embedding [0.25 0.75]} {:embedding-model "fixture"}]]
        (let [before (o/get-revision ctx oid)]
          (h/ok! (o/update-concept! ctx oid c {:changes change}))
          (is (= change (select-keys (o/get-concept-by-uri ctx oid "urn:min") (keys change))))
          (is (= (inc before) (o/get-revision ctx oid)))))
      (h/ok! (o/update-concept! ctx oid c {:changes {:embedding nil :embedding-model nil}}))
      (is (= [nil nil] ((juxt :embedding :embedding-model) (o/get-concept-by-uri ctx oid "urn:min"))))
      (let [before (h/capture ctx oid)]
        (is (h/anomaly? (o/update-concept! ctx other c {:changes {:label "Wrong graph"}})))
        (is (= before (h/capture ctx oid))))
      (h/ok! (o/create-concept! ctx oid {:uri "urn:embedded" :label "Embedded" :description "D" :embedding [1.0] :embedding-model "fixture"}))
      (is (= [[1.0] "fixture"] ((juxt :embedding :embedding-model) (o/get-concept-by-uri ctx oid "urn:embedded")))))))

(deftest invalid-contribution-envelopes-are-atomic
  (h/with-context [ctx]
    (let [oid (h/graph ctx) request (h/request ctx oid [])]
      (doseq [bad [(dissoc request :source) (dissoc request :contribution-id)
                   (dissoc request :concepts)
                   (assoc request :expected-revision 1.5) (assoc request :concepts {})
                   (assoc request :relationships {}) (assoc request :tbox []) (assoc request :abox {})
                   (assoc request :source {:source-id "invalid" :content-hash "hash"})
                   (assoc-in request [:source :content-hash] " ")
                   (assoc-in request [:source :producer] "invalid")]]
        (let [before (h/capture ctx oid)]
          (is (h/anomaly? (o/import-contribution! ctx oid bad)) (pr-str bad))
          (is (= before (h/capture ctx oid))))))))

(deftest public-command-options-have-consistent-retry-and-revision-semantics
  (doseq [kind [:ontology :concept :update :relationship :predicate]]
    (h/with-context [ctx]
      (let [oid (h/graph ctx) a (h/add! ctx oid "urn:a") _ (h/add! ctx oid "urn:b")
            request-id (random-uuid)
            invoke (fn [request]
                     (case kind
                       :ontology (o/create-ontology! ctx request)
                       :concept (o/create-concept! ctx oid request)
                       :update (o/update-concept! ctx oid (:concept-id a) request)
                       :relationship (o/create-relationship! ctx request)
                       :predicate (o/register-predicate! ctx oid request)))
            payload (case kind
                      :ontology {:name "Options"}
                      :concept (h/concept "urn:new")
                      :update {:changes {:label "Updated"}}
                      :relationship {:source-ontology-id oid :target-ontology-id oid
                                     :source-uri "urn:a" :target-uri "urn:b" :predicate "skos:related"}
                      :predicate {:predicate "app:options" :policy {:symmetric? true}})
            request (assoc payload :request-id request-id
                           :expected-revision (if (= kind :ontology) 0 (o/get-revision ctx oid)))
            result (h/ok! (invoke request))
            target (:ontology-id result)]
        (h/add! ctx target "urn:later")
        (let [before (h/graph-events ctx)]
          (is (= result (invoke request)))
          (is (= result (invoke (assoc request :command/id (random-uuid)
                                      :command/name :not/a-command :command/timestamp "invalid"))))
          (is (= :cognitect.anomalies/conflict
                 (:cognitect.anomalies/category (invoke (assoc request :expected-revision 999)))))
          (is (= :cognitect.anomalies/conflict
                 (:cognitect.anomalies/category
                   (invoke (assoc request :request-id (random-uuid) :expected-revision 999)))))
          (doseq [bad [(assoc request :request-id "invalid")
                       (assoc request :request-id nil)
                       (assoc request :expected-revision 1.5)]]
            (is (= :cognitect.anomalies/incorrect (:cognitect.anomalies/category (invoke bad)))))
          (is (= before (h/graph-events ctx))))))))

(deftest public-update-and-predicate-options-cannot-redirect-identities
  (h/with-context [ctx]
    (let [oid (h/graph ctx) other (h/graph ctx)
          a (h/add! ctx oid "urn:a") b (h/add! ctx other "urn:b")
          before (o/export-snapshot ctx other)]
      (h/ok! (o/update-concept! ctx oid (:concept-id a)
               {:ontology-id other :concept-id (:concept-id b) :changes {:label "Updated"}}))
      (h/ok! (o/register-predicate! ctx oid
               {:ontology-id other :predicate "app:local" :policy {}}))
      (is (= "Updated" (:label (o/get-concept-by-uri ctx oid "urn:a"))))
      (is (contains? (:predicate-policies (o/get-ontology ctx oid)) "app:local"))
      (is (= before (o/export-snapshot ctx other)))
      (is (= :cognitect.anomalies/incorrect
             (:cognitect.anomalies/category
               (o/import-contribution! ctx oid (assoc (h/request ctx oid []) :request-id (random-uuid)))))))))

(deftest concept-map-shares-list-scoping-and-error-contract
  (h/with-context [ctx]
    (let [oid (h/graph ctx) other (h/graph ctx)
          foreign (assoc ctx :tenant-id (random-uuid)) foreign-id (h/graph foreign)
          _ (h/add! ctx oid "urn:parent")
          child (h/ok! (o/create-concept! ctx oid (assoc (h/concept "urn:shared") :scope :child :broader ["urn:parent"])))
          _ (h/add! ctx other "urn:shared")
          _ (h/add! foreign foreign-id "urn:shared")]
      (is (= 3 (count (o/get-concept-map ctx))))
      (is (= (:concept-id child)
             (get-in (o/get-concept-map ctx {:ontology-id oid :broader-uri "urn:parent"})
                     [[oid "urn:shared"] :concept-id])))
      (doseq [filters [{} {:ontology-id oid} {:ontology-id (str oid)}
                       {:ontology-ids [oid other]} {:ontology-ids []}
                       {:scope :child} {:ontology-id oid :scope :child :broader-uri "urn:parent"}
                       {:ontology-id oid :broader-uri "urn:absent"}]]
        (is (= (into {} (map (fn [c] [[(:ontology-id c) (:uri c)] c])) (o/get-concepts ctx filters))
               (o/get-concept-map ctx filters))))
      (doseq [filters [nil [] {:ontology-id nil} {:ontology-ids "bad"}
                       {:ontology-id oid :ontology-ids [other]} {:scope []} {:broader-uri 42}
                       {:ontology-id (random-uuid)} {:ontology-ids [oid (random-uuid)]}
                       {:ontology-id foreign-id}]]
        (let [result (o/get-concept-map ctx filters)]
          (is (h/anomaly? result))
          (is (= (o/get-concepts ctx filters) result))))
      (is (= :cognitect.anomalies/forbidden
             (:cognitect.anomalies/category (o/get-concept-map (dissoc ctx :tenant-id) {:ontology-id oid})))))))

(deftest ontology-initializes-default-and-supplied-predicate-policies
  (h/with-context [ctx]
    (let [oid (h/graph ctx)
          defaults {"skos:broader" {:hierarchical? true :inverse "skos:narrower"}
                    "skos:narrower" {:inverse "skos:broader"}
                    "skos:related" {:symmetric? true}
                    "owl:sameAs" {:symmetric? true}
                    "rdfs:subClassOf" {:hierarchical? true}}
          supplied {"skos:related" {:symmetric? false} "urn:custom" {:cross-ontology? true}}
          custom (:ontology-id (h/ok! (o/create-ontology! ctx {:name "Custom" :predicate-policies supplied})))]
      (is (= defaults (:predicate-policies (o/get-ontology ctx oid))))
      (is (= (merge defaults supplied) (:predicate-policies (o/get-ontology ctx custom))))
      (is (= 1 (o/get-revision ctx custom))))))
