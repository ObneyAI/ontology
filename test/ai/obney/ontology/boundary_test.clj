(ns ai.obney.ontology.boundary-test
  "Public-boundary obligations from ontology-extraction-boundary.md.
   Fixtures use the real Grain event store; no ORC runtime or model provider."
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [ai.obney.grain.event-store-v3.interface :as es]
            [ai.obney.ontology.interface :as ontology]
            [ai.obney.ontology.core.state :as state]))

(defmacro with-context [[binding] & body]
  `(let [store# (es/start {:conn {:type :in-memory}
                          :event-pubsub nil :logger nil})
         ~binding {:event-store store# :tenant-id (random-uuid)}]
     (try ~@body (finally (es/stop store#)))))

(defn anomaly? [result] (some? (:cognitect.anomalies/category result)))
(defn conflict? [result]
  (= :cognitect.anomalies/conflict (:cognitect.anomalies/category result)))
(defn new-ontology [ctx name]
  (:ontology-id (ontology/create-ontology! ctx {:name name :scope :biology})))
(defn concept [uri label]
  {:uri uri :label label :description label :scope :biology})
(defn contribution [ctx oid concepts]
  {:ontology-id oid :contribution-id (random-uuid)
   :expected-revision (ontology/get-revision ctx oid)
   :source {:source-id (random-uuid) :content-hash "sha256:fixture"
            :producer {:name "table-parser" :version "1"}}
   :concepts concepts :relationships []})
(def graph-event-types
  #{:ontology/ontology-created :ontology/concept-created :ontology/concept-updated
    :ontology/relationship-created :ontology/predicate-registered :ontology/contribution-imported})
(defn events [ctx]
  (into [] (es/read (:event-store ctx)
                   {:tenant-id (:tenant-id ctx) :types graph-event-types})))

(deftest tenant-and-qualified-identity-isolation
  (with-context [a]
    (let [b (assoc a :tenant-id (random-uuid))
          oid (new-ontology a "A")
          other (new-ontology a "B")
          c (ontology/create-concept! a oid (concept "urn:shared" "First"))]
      (ontology/create-concept! a other (concept "urn:shared" "Second"))
      (is (uuid? (:concept-id c)))
      (is (= "First" (:label (ontology/get-concept-by-uri a oid "urn:shared"))))
      (is (= "Second" (:label (ontology/get-concept-by-uri a other "urn:shared"))))
      (is (thrown? clojure.lang.ArityException (ontology/get-concept-by-uri a "urn:shared")))
      (is (nil? (ontology/get-ontology b oid)))
      (is (empty? (ontology/list-ontologies b)))
      (is (anomaly? (ontology/update-concept! b oid (:concept-id c) {:label "stolen"})))
      (is (anomaly? (ontology/import-contribution! b (contribution a oid []))))
      (is (= "First" (:label (ontology/get-concept-by-uri a oid "urn:shared")))))))

(deftest manual-hierarchy-maintains-inverses-and-rejects-cycles
  (with-context [ctx]
    (let [oid (new-ontology ctx "Hierarchy")
          parent (ontology/create-concept! ctx oid (concept "urn:parent" "Parent"))
          child (ontology/create-concept! ctx oid
                  (assoc (concept "urn:child" "Child") :broader ["urn:parent"]))]
      (is (= #{"urn:child"} (set (:narrower (ontology/get-concept-by-uri ctx oid "urn:parent")))))
      (let [before (ontology/get-revision ctx oid)]
        (is (anomaly? (ontology/update-concept! ctx oid (:concept-id parent) {:broader ["urn:child"]})))
        (is (= before (ontology/get-revision ctx oid))))
      (is (not (anomaly? (ontology/update-concept! ctx oid (:concept-id child) {:broader []}))))
      (is (empty? (:narrower (ontology/get-concept-by-uri ctx oid "urn:parent")))))))

(deftest retry-identity-is-independent-of-source-content
  (with-context [ctx]
    (let [oid (new-ontology ctx "Imports")
          input (contribution ctx oid [(concept "urn:item" "Item")])
          result (ontology/import-contribution! ctx input)
          revision (ontology/get-revision ctx oid)
          event-count (count (events ctx))]
      (is (not (anomaly? result)) (pr-str result))
      (is (= result (ontology/import-contribution! ctx input)))
      (is (= event-count (count (events ctx))))
      (is (conflict? (ontology/import-contribution! ctx (assoc-in input [:concepts 0 :label] "Changed"))))
      (is (= revision (ontology/get-revision ctx oid)))
      ;; Identical source bytes may be reprocessed under a new contribution ID.
      (let [next-input (-> input
                           (assoc :contribution-id (random-uuid) :expected-revision revision)
                           (assoc-in [:source :producer :version] "2")
                           (assoc-in [:concepts 0 :description] "Reprocessed"))]
        (is (not (anomaly? (ontology/import-contribution! ctx next-input))))
        (is (= "Reprocessed" (:description (ontology/get-concept-by-uri ctx oid "urn:item"))))
        (is (= 2 (count (ontology/get-contributions ctx oid))))))))

(deftest stale-revisions-and-invalid-imports-are-atomic
  (with-context [ctx]
    (let [oid (new-ontology ctx "Atomic")
          stale (contribution ctx oid [(concept "urn:stale" "Stale")])
          _ (ontology/create-concept! ctx oid (concept "urn:existing" "Existing"))
          before (ontology/export-snapshot ctx oid)]
      (is (conflict? (ontology/import-contribution! ctx stale)))
      (is (= before (ontology/export-snapshot ctx oid)))
      (let [bad (contribution ctx oid [(assoc (concept "urn:bad" "Bad") :broader ["urn:missing"])])]
        (is (anomaly? (ontology/import-contribution! ctx bad)))
        (is (= before (ontology/export-snapshot ctx oid)))))))

(deftest incremental-import-preserves-identities-schema-and-instances
  (with-context [ctx]
    (let [oid (new-ontology ctx "Incremental")
          manual (ontology/create-concept! ctx oid (concept "urn:parent" "Parent"))
          tbox {:classes [{:uri "urn:class" :label "Class"}]
                :object-properties [] :datatype-properties []}
          abox [{:uri "urn:instance" :type "urn:class" :label "Instance"}]
          first-input (assoc (contribution ctx oid
                                [(assoc (concept "urn:child" "Child") :broader ["urn:parent"])])
                             :tbox tbox :abox abox)]
      (is (not (anomaly? (ontology/import-contribution! ctx first-input))))
      (is (= #{"urn:child"} (set (:narrower (ontology/get-concept-by-uri ctx oid "urn:parent")))))
      (is (not (anomaly? (ontology/import-contribution! ctx
                           (contribution ctx oid [(concept "urn:parent" "Updated parent")])))))
      (let [current (ontology/get-concept-by-uri ctx oid "urn:parent")
            snapshot (ontology/export-snapshot ctx oid)]
        (is (= (:concept-id manual) (or (:concept-id current) (:id current))))
        (is (= "Updated parent" (:label current)))
        (is (= tbox (:tbox snapshot)))
        (is (= abox (:abox snapshot)))))))

(deftest declarative-predicate-policy-has-no-agent-vocabulary
  (with-context [ctx]
    (let [oid (:ontology-id (ontology/create-ontology! ctx
                            {:name "Policy" :scope :biology
                             :predicate-policies {"app:peer" {:symmetric? true :cross-ontology? false}}}))
          other (new-ontology ctx "Other")]
      (doseq [[id uri] [[oid "urn:a"] [oid "urn:b"] [other "urn:c"]]]
        (ontology/create-concept! ctx id (concept uri uri)))
      (let [edge {:source-ontology-id oid :target-ontology-id oid
                  :source-uri "urn:a" :target-uri "urn:b" :predicate "app:peer"}]
        (is (not (anomaly? (ontology/create-relationship! ctx edge))))
        (is (anomaly? (ontology/create-relationship! ctx
                        (assoc edge :predicate "app:undeclared"))))
        (is (anomaly? (ontology/create-relationship! ctx
                        (assoc edge :target-ontology-id other :target-uri "urn:c"))))
        (is (= #{["urn:a" "urn:b"] ["urn:b" "urn:a"]}
               (set (map (juxt :source-uri :target-uri) (ontology/get-relationships ctx oid)))))))))

(deftest model-free-search-stays-scoped
  (with-context [ctx]
    (let [oid (new-ontology ctx "Search")
          other (new-ontology ctx "Collision")]
      (ontology/create-concept! ctx oid (concept "urn:shared" "Photosynthesis"))
      (ontology/create-concept! ctx other (concept "urn:shared" "Elsewhere"))
      (let [found (ontology/search ctx {:ontology-id oid :query-text "Photosynthesis" :limit 5})]
        (is (= ["urn:shared"] (mapv :uri found)))
        (is (every? #(= oid (:ontology-id %)) found)))
      (is (empty? (ontology/search ctx {:ontology-id other :query-text "Photosynthesis" :limit 5})))
      (is (empty? (filter #(str/starts-with? (str (ns-name %)) "ai.obney.orc.")
                          (all-ns)))))))

(deftest current-graph-events-replay-in-fresh-store
  (with-context [ctx]
    (let [oid (new-ontology ctx "Replay")
          parent (ontology/create-concept! ctx oid (concept "urn:parent" "Parent"))
          input (contribution ctx oid
                  [(assoc (concept "urn:child" "Child") :broader ["urn:parent"])])]
      (is (not (anomaly? (ontology/import-contribution! ctx input))))
      (is (not (anomaly? (ontology/update-concept! ctx oid (:concept-id parent)
                           {:description "Current description"}))))
      (let [snapshot (ontology/export-snapshot ctx oid)
            history (events ctx)]
        (with-context [fresh]
          (let [replayed (assoc fresh :tenant-id (:tenant-id ctx))]
            (is (not (anomaly?
                       (es/append (:event-store replayed)
                                  {:tenant-id (:tenant-id replayed)
                                   :events (mapv #(dissoc % :event/id :event/timestamp) history)}))))
            (is (= snapshot (ontology/export-snapshot replayed oid)))
            (is (= (ontology/get-revision ctx oid) (ontology/get-revision replayed oid)))
            (is (= #{"urn:child"}
                   (set (:narrower (ontology/get-concept-by-uri replayed oid "urn:parent")))))
            (is (= (ontology/get-contributions ctx oid)
                   (ontology/get-contributions replayed oid)))))))))

(deftest rdf-roundtrip-retains-literals-and-historical-revisions
  (with-context [ctx]
    (let [oid (new-ontology ctx "RDF")
          label "<urn:tree> <http://www.w3.org/2000/01/rdf-schema#label> \"Oak\"@en ."
          typed "<urn:tree> <urn:age> \"80\"^^<http://www.w3.org/2001/XMLSchema#integer> ."
          content (str label "\n" typed)
          request (assoc (dissoc (contribution ctx oid []) :concepts :relationships)
                         :content content)
          result (ontology/import-rdf! ctx request)]
      (is (not (anomaly? result)) (pr-str result))
      (let [snapshot (ontology/export-snapshot ctx oid)
            revision (:revision snapshot)
            c (ontology/get-concept-by-uri ctx oid "urn:tree")]
        (is (str/includes? (:ttl-output snapshot) label))
        (is (str/includes? (:ttl-output snapshot) typed))
        (is (not (anomaly? (ontology/update-concept! ctx oid (:id c) {:description "Later"}))))
        (is (= snapshot (ontology/export-snapshot ctx oid revision)))
        (is (< revision (:revision (ontology/export-snapshot ctx oid))))
        (let [copy (new-ontology ctx "Copy")
              imported (ontology/import-rdf! ctx
                         (assoc (dissoc (contribution ctx copy []) :concepts :relationships)
                                :content (:ttl-output snapshot)))
              copied (ontology/export-snapshot ctx copy)]
          (is (not (anomaly? imported)) (pr-str imported))
          (is (= "Oak" (:label (ontology/get-concept-by-uri ctx copy "urn:tree"))))
          (is (str/includes? (:ttl-output copied) label))
          (is (str/includes? (:ttl-output copied) typed)))))))

(deftest competing-contributions-check-revision-at-append
  (with-context [ctx]
    (let [oid (new-ontology ctx "Contended")
          left (contribution ctx oid [(concept "urn:left" "Left")])
          right (contribution ctx oid [(concept "urn:right" "Right")])
          before (ontology/get-revision ctx oid)
          read-state state/read-state
          barrier (java.util.concurrent.CyclicBarrier. 2)
          reads (atom 0)
          results (with-redefs [state/read-state
                               (fn [c]
                                 (let [s (read-state c)]
                                   ;; Both writers validate the same persisted prefix.
                                   (when (<= (swap! reads inc) 2)
                                     (.await barrier 10 java.util.concurrent.TimeUnit/SECONDS))
                                   s))]
                    (let [a (future (ontology/import-contribution! ctx left))
                          b (future (ontology/import-contribution! ctx right))]
                      [(deref a 15000 ::timeout) (deref b 15000 ::timeout)]))]
      (is (not-any? #{::timeout} results))
      (is (= 1 (count (filter conflict? results))) (pr-str results))
      (is (= 1 (count (remove anomaly? results))) (pr-str results))
      (is (= (inc before) (ontology/get-revision ctx oid)))
      (is (= 1 (count (ontology/get-concepts ctx {:ontology-id oid}))))
      (is (= 1 (count (ontology/get-contributions ctx oid)))))))

(deftest source-content-versions-remain-queryable
  (with-context [ctx]
    (let [oid (new-ontology ctx "Versioned source")
          first-input (contribution ctx oid [(concept "urn:item" "Version one")])
          source (:source first-input)]
      (is (not (anomaly? (ontology/import-contribution! ctx first-input))))
      (let [second-source (assoc source :content-hash "sha256:second-version")
            second-input (assoc (contribution ctx oid [(concept "urn:item" "Version two")])
                                :source second-source)]
        (is (not (anomaly? (ontology/import-contribution! ctx second-input))))
        (is (= #{source second-source} (set (map #(dissoc % :registered-at) (ontology/get-sources ctx oid)))))
        (is (= #{source second-source} (set (map :source (ontology/get-contributions ctx oid)))))
        (is (= "Version two" (:label (ontology/get-concept-by-uri ctx oid "urn:item"))))))))

(deftest declared-inverse-produces-reverse-predicate
  (with-context [ctx]
    (let [oid (:ontology-id (ontology/create-ontology! ctx
                             {:name "Inverse" :scope :biology
                              :predicate-policies {"app:owns" {:inverse "app:owned-by"}
                                                   "app:owned-by" {:inverse "app:owns"}}}))]
      (doseq [uri ["urn:a" "urn:b"]]
        (ontology/create-concept! ctx oid (concept uri uri)))
      (is (not (anomaly? (ontology/create-relationship! ctx
                           {:source-ontology-id oid :target-ontology-id oid
                            :source-uri "urn:a" :target-uri "urn:b" :predicate "app:owns"}))))
      (is (= #{["urn:a" "app:owns" "urn:b"] ["urn:b" "app:owned-by" "urn:a"]}
             (set (map (juxt :source-uri :predicate :target-uri)
                       (ontology/get-relationships ctx oid))))))))

(deftest semantic-resolution-requires-matching-model-identity
  (doseq [[incoming-model merge?] [["model:a" true] ["model:b" false] [nil false]]]
    (with-context [ctx]
      (let [oid (new-ontology ctx "Resolution")
            manual (ontology/create-concept! ctx oid
                     (assoc (concept "urn:canonical" "Forest")
                            :embedding [1.0 0.0] :embedding-model "model:a"))
            candidate (cond-> (assoc (concept "urn:incoming" "Woodland") :embedding [1.0 0.0])
                        incoming-model (assoc :embedding-model incoming-model))
            result (ontology/import-contribution! ctx
                     (assoc (contribution ctx oid [candidate])
                            :resolution-policy {:match-labels? true}))]
        (is (not (anomaly? result)) (pr-str result))
        (is (= (if merge? 1 2) (count (ontology/get-concepts ctx {:ontology-id oid}))))
        (if merge?
          (do (is (= "urn:canonical" (get (:canonical-map result) "urn:incoming")))
              (is (= (:concept-id manual) (:id (ontology/get-concept-by-uri ctx oid "urn:canonical")))))
          (is (some? (ontology/get-concept-by-uri ctx oid "urn:incoming"))))))))

(deftest canonical-resolution-rewrites-schema-instance-and-rdf-references
  (with-context [ctx]
    (let [oid (new-ontology ctx "References")
          manual (ontology/create-concept! ctx oid (concept "urn:canonical" "Tree"))
          request (assoc (contribution ctx oid [(concept "urn:alias" "Tree")])
                         :resolution-policy {:match-labels? true}
                         :tbox {:classes [{:uri "urn:alias" :label "Tree"}]
                                :object-properties [{:uri "urn:property" :domain "urn:alias" :range "urn:alias"}]
                                :datatype-properties []}
                         :abox [{:uri "urn:instance" :type "urn:alias"}]
                         :rdf-triples [{:subject "urn:instance" :predicate "rdf:type" :object-uri "urn:alias"}])
          result (ontology/import-contribution! ctx request)
          snapshot (ontology/export-snapshot ctx oid)]
      (is (not (anomaly? result)) (pr-str result))
      (is (= "urn:canonical" (get (:canonical-map result) "urn:alias")))
      (is (= (:concept-id manual) (:id (ontology/get-concept-by-uri ctx oid "urn:canonical"))))
      (is (= "urn:canonical" (get-in snapshot [:tbox :classes 0 :uri])))
      (is (= {:uri "urn:property" :domain "urn:canonical" :range "urn:canonical"}
             (get-in snapshot [:tbox :object-properties 0])))
      (is (= "urn:canonical" (get-in snapshot [:abox 0 :type])))
      (is (= "urn:canonical" (get-in snapshot [:rdf-triples 0 :object-uri])))
      (is (not (str/includes? (:ttl-output snapshot) "<urn:alias>"))))))

(deftest invalid-schema-import-does-not-partially-persist
  (with-context [ctx]
    (let [oid (new-ontology ctx "Invalid schema")
          before (ontology/export-snapshot ctx oid)
          before-events (events ctx)]
      (doseq [shape [{:tbox {:classes [{:label "Missing URI"}]}}
                     {:tbox {:object-properties [{:uri "urn:p" :domain "urn:missing"}]}}
                     {:abox [{:uri "urn:i" :type "urn:missing"}]}
                     {:abox [{:uri "urn:i" :properties []}]}]]
        (let [result (ontology/import-contribution! ctx
                       (merge (contribution ctx oid [(concept "urn:valid" "Valid")]) shape))]
          (is (anomaly? result) (pr-str result))
          (is (= before (ontology/export-snapshot ctx oid)))
          (is (= before-events (events ctx)))
          (is (empty? (ontology/get-contributions ctx oid))))))))

(deftest imported-hierarchy-cycles-are-rejected-without-changing-graph
  (with-context [ctx]
    (let [oid (new-ontology ctx "Imported cycle")
          _ (ontology/create-concept! ctx oid (concept "urn:parent" "Parent"))
          _ (ontology/create-concept! ctx oid
              (assoc (concept "urn:child" "Child") :broader ["urn:parent"]))
          before (ontology/export-snapshot ctx oid)
          result (ontology/import-contribution! ctx
                   (contribution ctx oid
                     [(assoc (concept "urn:parent" "Parent") :broader ["urn:child"])]))]
      (is (anomaly? result))
      (is (= before (ontology/export-snapshot ctx oid))))))

(deftest optional-vector-ranking-and-import-are-qualified
  (with-context [ctx]
    (let [oid (new-ontology ctx "Vectors")
          other (new-ontology ctx "Other vectors")]
      (doseq [[id model] [[oid "model:a"] [other "model:b"]]]
        (is (not (anomaly? (ontology/import-contribution! ctx
                             (contribution ctx id
                               [(assoc (concept "urn:shared" "No lexical match")
                                       :embedding [1.0 0.0] :embedding-model model)]))))))
      (let [options {:ontology-id oid :query-text "needle" :embedding-model "model:a"
                     :embed-query (constantly [1.0 0.0]) :limit 5}
            found (ontology/search ctx options)]
        (is (= [[oid "urn:shared"]] (mapv (juxt :ontology-id :uri) found)))
        (is (empty? (ontology/search ctx (assoc options :ontology-id other))))))))

(deftest resolution-does-not-collapse-established-identities
  (with-context [ctx]
    (let [oid (new-ontology ctx "Ambiguous established identities")
          first-concept (ontology/create-concept! ctx oid (concept "urn:one" "Shared label"))
          second-concept (ontology/create-concept! ctx oid (concept "urn:two" "Shared label"))
          before (ontology/export-snapshot ctx oid)
          before-events (events ctx)
          input (assoc (contribution ctx oid [(concept "urn:third" "Shared label")])
                       :resolution-policy {:match-labels? true})
          result (ontology/import-contribution! ctx input)]
      (is (conflict? result) (pr-str result))
      (is (= before (ontology/export-snapshot ctx oid)))
      (is (= (:revision before) (ontology/get-revision ctx oid)))
      (is (= before-events (events ctx)))
      (is (= (:concept-id first-concept) (:id (ontology/get-concept-by-uri ctx oid "urn:one"))))
      (is (= (:concept-id second-concept) (:id (ontology/get-concept-by-uri ctx oid "urn:two"))))
      (is (empty? (ontology/get-contributions ctx oid))))))

(deftest contribution-history-retains-original-input
  (with-context [ctx]
    (let [oid (new-ontology ctx "Original inputs")
          _ (ontology/create-concept! ctx oid (concept "urn:canonical" "Canonical"))
          input (assoc (contribution ctx oid [(concept "urn:alias" "Canonical")])
                       :resolution-policy {:match-labels? true})
          result (ontology/import-contribution! ctx input)
          record (first (ontology/get-contributions ctx oid))]
      (is (not (anomaly? result)) (pr-str result))
      (is (= input (:input record)))
      (is (= "urn:canonical" (get (:canonical-map record) "urn:alias")))
      (is (= "urn:alias" (get-in record [:input :concepts 0 :uri]))))))

(deftest absent-vector-models-are-not-comparable
  (with-context [ctx]
    (let [oid (new-ontology ctx "Unidentified embedding")]
      (ontology/create-concept! ctx oid
        (assoc (concept "urn:vector" "Unrelated") :embedding [1.0 0.0]))
      (is (empty? (ontology/search ctx {:ontology-id oid :query-text "needle"
                                       :embed-query (constantly [1.0 0.0]) :limit 5}))))))
