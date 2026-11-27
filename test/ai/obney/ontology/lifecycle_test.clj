(ns ai.obney.ontology.lifecycle-test
  (:require [ai.obney.ontology.api-schemas-test :refer [checked]]
            [ai.obney.ontology.interface :as o]
            [ai.obney.ontology.support :as h]
            [clojure.test :refer [deftest is]]
            [cognitect.anomalies :as anom]))

(defn- link! [ctx so su predicate to tu]
  (h/ok! (o/create-relationship! ctx {:source-ontology-id so :source-uri su :predicate predicate
                                      :target-ontology-id to :target-uri tu :metadata {:from su}})))

(defn- check-views [ctx oid]
  (let [records (o/get-concepts ctx {:ontology-id oid})]
    (is (= records (:concepts (o/export-snapshot ctx oid))))
    (doseq [{:keys [uri concept-id] :as concept} records]
      (is (= concept (o/get-concept ctx oid concept-id) (o/get-concept-by-uri ctx oid uri))))))

(deftest edits-update-qualified-views-history-and-retries
  (h/with-context [ctx]
    (let [a (h/graph ctx) b (h/graph ctx) unrelated (h/graph ctx)
          original (:concept-id (h/add! ctx a "urn:old"))
          target (:concept-id (h/add! ctx a "urn:target"))
          peer (:concept-id (h/add! ctx b "urn:old"))
          _ (h/ok! (o/register-predicate! ctx b {:predicate "urn:link" :policy {:cross-ontology? true :inverse "urn:back"}}))
          edge (link! ctx b "urn:old" "urn:link" a "urn:old")
          _ (h/ok! (o/create-concept! ctx a (assoc (h/concept "urn:child") :broader ["urn:old"])))
          _ (h/ok! (o/import-contribution! ctx a
                     (assoc (h/request ctx a [(assoc (h/concept "urn:alias") :label "urn:old")])
                            :resolution-policy {:match-labels? true})))
          initial-mappings (o/get-canonical-mappings ctx a)
          history (o/get-contributions ctx a)
          snapshots (atom [])
          retries (atom [])]
      (doseq [[command id request current] [['rename-concept! original {:new-uri "urn:moved"} "urn:moved"]
                                            ['merge-concepts! original {:target-concept-id target} "urn:target"]
                                            ['delete-concept! target {} nil]]]
        (check-views ctx a)
        (check-views ctx b)
        (let [before (mapv #(o/export-snapshot ctx %) [a b])
              previous-uri (:uri (o/get-concept ctx a id))
              options (assoc request :request-id (random-uuid) :expected-revision (:revision (first before)))
              result (h/ok! (checked command ctx a id options))]
          (swap! snapshots conj before)
          (swap! retries conj [command id options result])
          (is (= (or current previous-uri) (:uri result)))
          (is (= (mapv #(inc (:revision %)) before) (mapv #(o/get-revision ctx %) [a b])))
          (is (= 1 (o/get-revision ctx unrelated)))
          (is (nil? (o/get-concept-by-uri ctx a previous-uri)))
          (is (= peer (:concept-id (o/get-concept-by-uri ctx b "urn:old"))))
          (is (= history (o/get-contributions ctx a)))
          (is (= (if current (update-vals initial-mappings (constantly current)) {}) (o/get-canonical-mappings ctx a)))
          (is (= (if current #{current} #{}) (:broader (o/get-concept-by-uri ctx a "urn:child"))))
          (is (= (if current #{[a current]} #{}) (get-in (o/get-concept ctx b peer) [:relations "urn:link"] #{})))
          (when current
            (let [redirected (first (filter #(= "urn:link" (:predicate %)) (o/get-relationships ctx b)))]
              (is (= (:relationship-id edge) (:relationship-id redirected)))
              (is (= (:metadata edge) (:metadata redirected)))))
          (check-views ctx a)
          (check-views ctx b)))
      (is (nil? (o/get-concept ctx a original)))
      (is (nil? (o/get-concept ctx a target)))
      (is (empty? (o/search ctx a {:seed-uris ["urn:target"]})))
      (h/with-context [fresh]
        (h/replay! ctx fresh)
        (doseq [c [ctx fresh]]
          (let [events (h/graph-events c)]
            (doseq [[command id opts result] @retries]
              (is (= result (checked command c a id opts)))
              (is (= ::anom/conflict (::anom/category (checked command c a id (dissoc opts :expected-revision))))))
            (is (= events (h/graph-events c))))
          (doseq [pair @snapshots snapshot pair]
            (is (= snapshot (o/export-snapshot c (:ontology-id snapshot) (:revision snapshot)))))
          (is (= (o/export-snapshot ctx a) (o/export-snapshot c a)))
          (is (= (o/get-canonical-mappings ctx a) (o/get-canonical-mappings c a))))))))

(deftest merge-keeps-target-data-and-deduplicates-logical-edges
  (h/with-context [ctx]
    (let [oid (h/graph ctx)
          source (:concept-id (h/ok! (o/create-concept! ctx oid (assoc (h/concept "s") :alt-labels ["extra" "shared"]))))
          target (:concept-id (h/ok! (o/create-concept! ctx oid
                                      (assoc (h/concept "t") :alt-labels ["shared"] :attributes {:chosen true}))))
          _ (h/add! ctx oid "p")
          retained (link! ctx oid "p" "skos:related" oid "t")]
      (link! ctx oid "s" "skos:related" oid "p")
      (link! ctx oid "s" "skos:broader" oid "t")
      (is (= target (:concept-id (h/ok! (o/merge-concepts! ctx oid source {:target-concept-id target})))))
      (let [c (o/get-concept ctx oid target) edges (o/get-relationships ctx oid)]
        (is (= ["t" "Description t" {:chosen true} ["shared" "s" "extra"]]
               ((juxt :label :description :attributes :alt-labels) c)))
        (is (= 2 (count edges)))
        (is (= #{(:relationship-id retained)} (set (map :relationship-id edges))))
        (is (= #{"p"} (:related c)))
        (is (empty? (:broader c)))))))

(deftest invalid-edits-are-atomic
  (h/with-context [ctx]
    (let [oid (h/graph ctx) foreign (h/graph ctx)
          a (:concept-id (h/add! ctx oid "a")) b (:concept-id (h/add! ctx oid "b"))
          alien (:concept-id (h/add! ctx foreign "b"))]
      (h/add! ctx oid "middle")
      (link! ctx oid "a" "skos:broader" oid "middle")
      (link! ctx oid "middle" "skos:broader" oid "b")
      (let [before (h/capture ctx oid)]
        (doseq [[category result]
                [[::anom/incorrect (o/merge-concepts! ctx oid a {:target-concept-id b})]
                 [::anom/incorrect (o/merge-concepts! ctx oid a {:target-concept-id a})]
                 [::anom/not-found (o/merge-concepts! ctx oid a {:target-concept-id alien})]
                 [::anom/not-found (o/merge-concepts! ctx oid alien {:target-concept-id b})]
                 [::anom/not-found (o/rename-concept! ctx oid alien {:new-uri "c"})]
                 [::anom/not-found (o/delete-concept! ctx foreign a {})]
                 [::anom/conflict (o/rename-concept! ctx oid a {:new-uri "b"})]
                 [::anom/conflict (o/rename-concept! ctx oid a {:new-uri "a"})]
                 [::anom/incorrect (o/rename-concept! ctx oid a {:new-uri " "})]
                 [::anom/incorrect (o/rename-concept! ctx oid a {:new-uri "urn:bad%ZZ"})]
                 [::anom/conflict (o/delete-concept! ctx oid a {:expected-revision 1})]
                 [::anom/forbidden (o/delete-concept! (dissoc ctx :tenant-id) oid a {})]
                 [::anom/not-found (o/delete-concept! (assoc ctx :tenant-id (random-uuid)) oid a {})]]]
          (is (= category (::anom/category result)) (pr-str result)))
        (is (= before (h/capture ctx oid)))))))

(deftest imported-identities-rdf-and-schema-survive-edits
  (h/with-context [ctx]
    (let [oid (h/graph ctx) uri "urn:old" moved "urn:new" target "urn:target"
          _ (h/add! ctx oid target)
          source-input (assoc (dissoc (h/request ctx oid []) :concepts)
                              :content (str "<urn:old> <urn:label> \"urn:old\" .\n"
                                            "<urn:object> <http://www.w3.org/2004/02/skos/core#related> <urn:old> ."))
          _ (h/ok! (o/import-rdf! ctx oid source-input))
          id (:concept-id (o/get-concept-by-uri ctx oid uri))
          target-id (:concept-id (o/get-concept-by-uri ctx oid target))]
      (h/ok! (o/import-contribution! ctx oid
               (assoc (h/request ctx oid []) :tbox {:object-properties [{:uri "urn:prop" :domain uri :range uri}]}
                      :abox [{:uri "urn:i" :type uri :properties {"urn:text" uri}}])))
      (let [before (h/capture ctx oid)]
        (is (= ::anom/incorrect (::anom/category (o/rename-concept! ctx oid id {:new-uri "relative"}))))
        (is (= before (h/capture ctx oid))))
      (doseq [[old new command opts] [[uri moved o/rename-concept! {:new-uri moved}]
                                      [moved target o/merge-concepts! {:target-concept-id target-id}]]]
        (let [before (h/capture ctx oid)]
          (is (= ::anom/conflict (::anom/category (o/delete-concept! ctx oid id {}))))
          (is (= before (h/capture ctx oid))))
        (h/ok! (command ctx oid id opts))
        (let [snap (o/export-snapshot ctx oid)]
          (is (= new (get-in snap [:abox 0 :type])))
          (is (= [new new] ((juxt :domain :range) (get-in snap [:tbox :object-properties 0]))))
          (is (= uri (get-in snap [:abox 0 :properties "urn:text"])))
          (is (some #(and (= new (:subject %)) (= uri (:literal %))) (:rdf-triples snap)))
          (is (some #(= new (:object-uri %)) (:rdf-triples snap)))
          (is (nil? (o/get-concept-by-uri ctx oid old))))
        (h/ok! (o/import-contribution! ctx oid (h/request ctx oid [(h/concept old)])))
        (is (not= id (:concept-id (o/get-concept-by-uri ctx oid old))))
        (check-views ctx oid))
      (let [object (:concept-id (o/get-concept-by-uri ctx oid "urn:object"))]
        (h/ok! (o/delete-concept! ctx oid object {}))
        (is (not-any? #(= "urn:object" (:subject %)) (:rdf-triples (o/export-snapshot ctx oid)))))
      (is (nil? (o/get-concept ctx oid id))))))

(deftest explicit-schema-declarations-survive-concept-deletion
  (h/with-context [ctx]
    (let [oid (h/graph ctx) uri "urn:class"
          input (assoc (h/request ctx oid [(h/concept uri)])
                       :tbox {:classes [{:uri uri}] :object-properties [{:uri "urn:p" :domain uri}]}
                       :abox [{:uri "urn:i" :type uri}])]
      (h/ok! (o/import-contribution! ctx oid input))
      (let [original (o/export-snapshot ctx oid)
            id (:concept-id (o/get-concept-by-uri ctx oid uri))]
        (h/ok! (o/delete-concept! ctx oid id {}))
        (is (nil? (o/get-concept ctx oid id)))
        (is (= (select-keys original [:tbox :abox])
               (select-keys (o/export-snapshot ctx oid) [:tbox :abox])))))))
