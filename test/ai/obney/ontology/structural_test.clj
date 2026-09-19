(ns ai.obney.ontology.structural-test
  (:require [clojure.test :refer [deftest is]]
            [ai.obney.ontology.interface :as o]
            [ai.obney.ontology.support :as h]
            [ai.obney.ontology.durable-test :as durable]
            [ai.obney.ontology.core.streams :as streams]
            [ai.obney.ontology.core.state :as state]
            [ai.obney.grain.event-store-v3.interface :as es]
            [ai.obney.grain.event-store-v3.interface.protocol :as esp]
            [ai.obney.grain.read-model-processor-v2.interface :as rmp]))

(defn observed [ctx]
  (let [reads (atom []) store (:event-store ctx)]
    [(assoc ctx :event-store
            (reify esp/EventStore
              (start [this] this) (stop [this] this)
              (tenants [_] (es/tenants store))
              (append [_ args] (es/append store args))
              (read [_ query]
                (let [entry {:query query :consumed (atom [])} events (es/read store query)]
                  (swap! reads conj entry)
                  (reify clojure.lang.IReduceInit
                    (reduce [_ f init]
                      (reduce (fn [acc e] (swap! (:consumed entry) conj e) (f acc e)) init events))))))) reads]))
(defn consumed [reads] (mapcat #(deref (:consumed %)) @reads))
(defn query-maps [reads] (mapcat #(let [q (:query %)] (if (vector? q) q [q])) @reads))

(deftest point-streams-use-grain-and-incremental-watermarks
  (h/with-context [ctx]
    (let [oid (h/graph ctx) other (h/graph ctx) c (h/add! ctx oid "urn:one")
          _ (h/add! ctx oid "urn:unrelated") _ (h/add! ctx other "urn:one")
          [ctx reads] (observed ctx) calls (atom 0) processor rmp/p]
      (with-redefs [rmp/p (fn [& args] (swap! calls inc) (apply processor args))]
        (is (= "urn:one" (:uri (o/get-concept-by-uri ctx oid "urn:one"))))
        (is (pos? @calls))
        (is (= #{:ontology/ontology-created :ontology/concept-created} (set (map :event/type (consumed reads)))))
        (is (= 2 (count (consumed reads))))
        (is (every? :tags (query-maps reads)))
        (reset! reads [])
        (o/get-concept-by-uri ctx oid "urn:one")
        (is (empty? (consumed reads)))
        (is (every? :after (query-maps reads)))
        (h/ok! (o/update-concept! ctx oid (:concept-id c) {:label "Updated"}))
        (h/add! ctx other "urn:noise")
        (reset! reads [])
        (is (= "Updated" (:label (o/get-concept-by-uri ctx oid "urn:one"))))
        (is (= [:ontology/concept-updated] (mapv :event/type (consumed reads))))
        (reset! reads [])
        (is (nil? (o/get-concept-by-uri ctx oid "urn:missing")))
        (is (empty? (consumed reads)))))))

(deftest parent-streams-track-removed-and-added-edges
  (h/with-context [ctx]
    (let [oid (h/graph ctx) _ (h/add! ctx oid "urn:a") _ (h/add! ctx oid "urn:b")
          child (h/ok! (o/create-concept! ctx oid (assoc (h/concept "urn:child") :broader ["urn:a"])))]
      (is (= #{"urn:child"} (:narrower (o/get-concept-by-uri ctx oid "urn:a"))))
      (h/ok! (o/update-concept! ctx oid (:concept-id child) {:broader ["urn:b"]}))
      (let [tags (:event/tags (last (h/graph-events ctx)))]
        (is (every? tags (map #(streams/concept-tag oid %) ["urn:a" "urn:b" "urn:child"]))))
      (is (= #{} (:narrower (o/get-concept-by-uri ctx oid "urn:a"))))
      (is (= #{"urn:child"} (:narrower (o/get-concept-by-uri ctx oid "urn:b"))))
      (is (= (o/get-concepts ctx {:ontology-id oid})
             (mapv #(o/get-concept-by-uri ctx oid %) ["urn:a" "urn:b" "urn:child"]))))))

(deftest contributions-tag-changes-without-tagging-unchanged-snapshot-records
  (h/with-context [ctx]
    (let [oid (h/graph ctx) _ (h/add! ctx oid "urn:unchanged")
          input (h/request ctx oid [(h/concept "urn:added")])
          result (h/ok! (o/import-contribution! ctx input))
          e (last (h/graph-events ctx)) tags (:event/tags e)
          [ctx reads] (observed ctx)]
      (is (= 1 (count (get-in e [:changes :concepts :upserts]))))
      (is (contains? tags (streams/concept-tag oid "urn:added")))
      (is (not (contains? tags (streams/concept-tag oid "urn:unchanged"))))
      (is (contains? tags [:contribution (:contribution-id input)]))
      (is (contains? tags [:source (get-in input [:source :source-id])]))
      (h/add! ctx oid "urn:later")
      (reset! reads [])
      (is (= result (o/import-contribution! ctx input)))
      (is (= 1 (count (consumed reads))))
      (is (every? #(= #{(streams/request-tag oid (:contribution-id input))} (:tags %)) (query-maps reads)))
      (is (= "urn:added" (:uri (o/get-concept-by-uri ctx oid "urn:added")))))))

(deftest request-streams-isolate-ontology-and-tenant-on-both-stores
  (doseq [backend [:memory :sqlite]]
    (let [file (when (= backend :sqlite) (durable/temp-db))
          ctx (if file {:event-store (durable/open-store file) :tenant-id (random-uuid)} (h/context))
          other-tenant (assoc ctx :tenant-id (random-uuid))]
      (try
        (let [oid (h/graph ctx) other-oid (h/graph ctx) request-id (random-uuid)
              input (assoc (h/request ctx oid [(h/concept "urn:first")]) :contribution-id request-id)
              result (h/ok! (o/import-contribution! ctx input))]
          (is (nil? (state/read-request ctx other-oid request-id)))
          (is (nil? (state/read-request other-tenant oid request-id)))
          (h/ok! (h/send! other-tenant :ontology/create-ontology {:name "Other tenant"} oid))
          (doseq [[scope id uri] [[ctx other-oid "urn:other-ontology"] [other-tenant oid "urn:other-tenant"]]]
            (let [request (assoc (h/request scope id [(h/concept uri)]) :contribution-id request-id)
                  accepted (h/ok! (o/import-contribution! scope request))
                  before (count (h/graph-events scope))]
              (is (= accepted (o/import-contribution! scope request)))
              (is (= before (count (h/graph-events scope))))
              (is (h/rejected? #(o/import-contribution! scope (assoc request :concepts [(h/concept "urn:changed")]))))))
          (is (= result (o/import-contribution! ctx input))))
        (finally
          (es/stop (:event-store ctx))
          (when file (durable/cleanup file)))))))

(deftest relationships-ignore-label-only-events
  (h/with-context [ctx]
    (let [oid (h/graph ctx) parent (h/add! ctx oid "urn:parent")
          _ (h/ok! (o/create-concept! ctx oid (assoc (h/concept "urn:child") :broader ["urn:parent"])))
          [ctx reads] (observed ctx)]
      (is (= 2 (count (o/get-relationships ctx oid))))
      (is (= 2 (count (consumed reads))))
      (h/ok! (o/update-concept! ctx oid (:concept-id parent) {:label "Renamed"}))
      (reset! reads [])
      (is (= 2 (count (o/get-relationships ctx oid))))
      (is (empty? (consumed reads))))))

(deftest caches-isolate-store-instances-even-for-the-same-tenant
  (h/with-context [a]
    (h/with-context [b]
      (let [b (assoc b :tenant-id (:tenant-id a)) oid (h/graph a)]
        (h/add! a oid "urn:only-a")
        (is (some? (o/get-concept-by-uri a oid "urn:only-a")))
        (is (nil? (o/get-concept-by-uri b oid "urn:only-a")))
        (is (nil? (o/get-ontology b oid)))))))

(deftest historical-projections-remain-bounded-on-both-stores
  (doseq [backend [:memory :sqlite]]
    (let [file (when (= backend :sqlite) (durable/temp-db))
          ctx (if file {:event-store (durable/open-store file) :tenant-id (random-uuid)} (h/context))]
      (try
        (let [oid (h/graph ctx) _ (h/add! ctx oid "urn:before")
              expected (o/export-snapshot ctx oid) revision (:revision expected)
              [ctx reads] (observed ctx)]
          (is (= expected (o/export-snapshot ctx oid revision)))
          (h/add! ctx oid "urn:after")
          (o/export-snapshot ctx oid revision)
          (reset! reads [])
          (is (= expected (o/export-snapshot ctx oid revision)))
          (is (empty? (consumed reads)))
          (is (not-any? #(and (:after %) (:as-of %)) (query-maps reads))))
        (finally (es/stop (:event-store ctx)) (when file (durable/cleanup file)))))))

(deftest scoped-views-use-the-originating-ontology-predicate-policy
  (h/with-context [ctx]
    (let [a (h/graph ctx) b (h/graph ctx)]
      (h/add! ctx a "urn:a") (h/add! ctx b "urn:b")
      (h/ok! (o/register-predicate! ctx a "urn:forward" {:inverse "urn:back" :cross-ontology? true}))
      (h/ok! (o/create-relationship! ctx {:source-ontology-id a :source-uri "urn:a"
                                         :target-ontology-id b :target-uri "urn:b" :predicate "urn:forward"}))
      (is (= #{[a "urn:a"]} (get-in (o/get-concept-by-uri ctx b "urn:b") [:relations "urn:back"])))
      (is (= #{"urn:forward" "urn:back"} (set (map :predicate (o/get-relationships ctx b)))))
      (is (= [(o/get-concept-by-uri ctx b "urn:b")] (o/get-concepts ctx {:ontology-id b}))))))

(deftest independent-ontology-writes-do-not-conflict
  (h/with-context [ctx]
    (let [a (h/graph ctx) b (h/graph ctx)
          read-state (requiring-resolve 'ai.obney.ontology.core.state/read-state)
          original @read-state barrier (java.util.concurrent.CyclicBarrier. 2)]
      (with-redefs-fn {read-state (fn [ctx]
                                  (let [s (original ctx)]
                                    (.await barrier 10 java.util.concurrent.TimeUnit/SECONDS) s))}
        (fn []
          (let [left (future (o/create-concept! ctx a (h/concept "urn:left")))
                right (future (o/create-concept! ctx b (h/concept "urn:right")))]
            (is (not (h/anomaly? (deref left 15000 {:cognitect.anomalies/category :timeout}))))
            (is (not (h/anomaly? (deref right 15000 {:cognitect.anomalies/category :timeout}))))))))))
