(ns ai.obney.ontology.indexes-test
  (:require [clojure.test :refer [deftest is]]
            [clojure.test.check :as tc]
            [clojure.test.check.generators :as gen]
            [clojure.test.check.properties :as prop]
            [ai.obney.ontology.core.indexes :as idx]
            [ai.obney.ontology.core.state :as state]
            [ai.obney.ontology.core.commands :as commands]
            [ai.obney.ontology.core.entity-resolver :as resolver]
            [ai.obney.ontology.core.delta :as delta]
            [ai.obney.ontology.interface :as o]
            [ai.obney.ontology.support :as h]
            [ai.obney.ontology.durable-test :as durable]
            [ai.obney.grain.event-store-v3.interface :as es]))

(defn graph-state [ctx oid] (state/read-state (assoc ctx :ontology/ids #{oid})))
(defn check-indexes [s]
  (is (= (:indexes (idx/rebuild (dissoc s :indexes))) (:indexes s)))
  (doseq [[oid uris] (get-in s [:indexes :concept-uris])]
    (is (= (set (keep (fn [[[id uri] _]] (when (= oid id) uri)) (:concepts s))) uris)))
  (doseq [[oid edges] (get-in s [:indexes :outgoing])]
    (is (= (set (keep (fn [[key e]] (when (= oid (:source-ontology-id e)) key)) (:edges s))) edges))))

(deftest indexes-follow-manual-and-contribution-updates-and-replay
  (h/with-context [ctx]
    (let [oid (h/graph ctx) _ (h/add! ctx oid "urn:a") _ (h/add! ctx oid "urn:b")
          child (h/ok! (o/create-concept! ctx oid (assoc (h/concept "urn:child") :label "Old_Label"
                                                       :alt-labels ["Alias"] :broader ["urn:a"]
                                                       :embedding-model "m" :embedding [1 0])))]
      (check-indexes (graph-state ctx oid))
      (h/ok! (o/update-concept! ctx oid (:concept-id child)
                               {:label "New-Label" :broader ["urn:b"] :embedding [1 0 0]}))
      (let [s (graph-state ctx oid)]
        (check-indexes s)
        (is (= #{[oid "urn:child"]} (get-in s [:indexes :labels [oid :domain "new label"]])))
        (is (nil? (get-in s [:indexes :labels [oid :domain "old label"]])))
        (is (= #{[oid "urn:child"]} (get-in s [:indexes :labels [oid :domain "alias"]])))
        (is (nil? (get-in s [:indexes :embeddings [oid :domain "m" 2]])))
        (is (= #{[oid "urn:child"]} (get-in s [:indexes :embeddings [oid :domain "m" 3]])))
        (is (= #{(state/edge-key (state/edge oid "urn:child" "skos:broader" "urn:b"))}
               (state/parent-keys s oid "urn:child"))))
      (let [input (h/request ctx oid [(assoc (h/concept "urn:child") :broader [] :alt-labels ["Replacement"])])
            result (h/ok! (o/import-contribution! ctx input)) s (graph-state ctx oid)]
        (check-indexes s)
        (is (= 3 (:concept-count result)))
        (is (= 0 (:relationship-count result)))
        (is (empty? (get-in s [:indexes :parents])))
        (is (empty? (get-in s [:indexes :hierarchy])))
        (is (nil? (get-in s [:indexes :labels [oid :domain "alias"]])))
        (h/with-context [fresh]
          (h/replay! ctx fresh)
          (is (= (:indexes s) (:indexes (graph-state fresh oid)))))))))

(deftest imports-validate-once-and-use-only-the-affected-parent-index
  (h/with-context [ctx]
    (let [oid (h/graph ctx) _ (h/add! ctx oid "urn:root")
          input (h/request ctx oid (mapv #(assoc (h/concept (str "urn:" %)) :broader ["urn:root"]) (range 100)))
          validations (atom 0) replacements (atom 0) parent-visits (atom [])
          validate commands/validate-graph! replace state/replace-parents parent-keys state/parent-keys]
      (graph-state ctx oid)
      (with-redefs [commands/validate-graph! (fn [& args] (swap! validations inc) (apply validate args))
                    state/replace-parents (fn [& args] (swap! replacements inc) (apply replace args))
                    idx/rebuild (fn [& _] (throw (ex-info "Warm command rebuilt indexes" {})))
                    idx/resolution-candidates (fn [& _] (throw (ex-info "Disabled matching prepared candidates" {})))]
        (h/ok! (o/import-contribution! ctx input)))
      (is (= 1 @validations))
      (is (zero? @replacements))
      (graph-state ctx oid)
      (let [input (h/request ctx oid [(assoc (h/concept "urn:1") :broader [])])]
        (reset! validations 0)
        (with-redefs [commands/validate-graph! (fn [& args] (swap! validations inc) (apply validate args))
                      state/parent-keys (fn [s id uri]
                                          (let [ks (parent-keys s id uri)] (swap! parent-visits conj ks) ks))]
          (h/ok! (o/import-contribution! ctx input)))
        (is (= 1 @validations))
        (is (= [#{(state/edge-key (state/edge oid "urn:1" "skos:broader" "urn:root"))}] @parent-visits))
        (is (= 99 (count (get-in (graph-state ctx oid) [:indexes :outgoing oid]))))))))

(def matching-record
  (gen/let [label (gen/elements ["Alpha" " alpha " "Beta" "New-Label" ""])
            alias (gen/elements [[] ["ALPHA"] ["new_label"]])
            type (gen/elements [:domain "Other"])
            model (gen/elements [nil "m" "other"])
            embedding (gen/elements [nil [1 0] [0 1] [0 0] [1 0 0]])]
    {:label label :alt-labels alias :entity-type type :embedding-model model :embedding embedding}))
(defn matching-result [result]
  (assoc (select-keys result [:canonical-map :exact-matches :semantic-matches]) :matches (set (:matches result))))
(deftest indexed-resolution-agrees-with-unfiltered-resolution
  (let [oid (random-uuid)
        result (tc/quick-check 300
                 (prop/for-all [old (gen/vector matching-record 0 15)
                                incoming (gen/vector matching-record 0 5)]
                   (let [old (mapv #(assoc %1 :uri (str "urn:old:" %2)) old (range))
                         incoming (mapv #(assoc %1 :uri (str "urn:new:" %2) :source-id :incoming) incoming (range))
                         s (idx/rebuild {:concepts (into {} (map (fn [c] [[oid (:uri c)] c])) old)})
                         uris (get-in s [:indexes :concept-uris oid] #{})
                         config {:similarity-threshold 0.5}]
                     (= (matching-result (resolver/resolve-incremental incoming old uris config))
                        (matching-result (resolver/resolve-incremental incoming (idx/resolution-candidates s oid incoming) uris config)))))
                 :seed 20260918)]
    (is (:pass? result) (pr-str result))))

(deftest hierarchy-and-connections-survive-removing-one-of-parallel-edges
  (let [a (random-uuid) b (random-uuid)
        e1 (assoc (state/edge a "urn:a" "p" "urn:b") :target-ontology-id b)
        e2 (assoc e1 :predicate "q")
        s (idx/rebuild {:ontologies {a {:predicate-policies {"p" {:hierarchical? true} "q" {:hierarchical? true}}}}})
        s (-> s (state/put-edge e1) (state/put-edge e2))
        after (delta/apply-changes s a {:relationships {:removals [(state/edge-key e1)]}})]
    (check-indexes after)
    (is (= 1 (get-in after [:indexes :connections a b])))
    (is (= 1 (get-in after [:indexes :connections b a])))
    (is (= 1 (get-in after [:indexes :hierarchy [a "urn:a"] [b "urn:b"]])))
    (let [empty (state/remove-edge after (state/edge-key e2))]
      (check-indexes empty)
      (is (empty? (get-in empty [:indexes :connections])))
      (is (empty? (get-in empty [:indexes :hierarchy]))))))

(deftest sqlite-reopen-reconstructs-command-indexes
  (let [file (durable/temp-db) store (durable/open-store file) tenant (random-uuid)
        ctx {:event-store store :tenant-id tenant}
        saved (try
                (let [oid (h/graph ctx)]
                  (h/add! ctx oid "urn:parent")
                  (h/ok! (o/import-contribution! ctx (h/request ctx oid [(assoc (h/concept "urn:child") :broader ["urn:parent"])])))
                  {:oid oid :indexes (:indexes (graph-state ctx oid))})
                (finally (es/stop store)))]
    (try
      (let [store (durable/open-store file) ctx {:event-store store :tenant-id tenant} oid (:oid saved)]
        (try
          (is (= (:indexes saved) (:indexes (graph-state ctx oid))))
          (h/ok! (o/import-contribution! ctx (h/request ctx oid [(assoc (h/concept "urn:child") :broader [])])))
          (check-indexes (graph-state ctx oid))
          (is (empty? (get-in (graph-state ctx oid) [:indexes :hierarchy])))
          (finally (es/stop store))))
      (finally (durable/cleanup file)))))

(deftest enabled-import-resolution-receives-indexed-candidates
  (h/with-context [ctx]
    (let [oid (h/graph ctx)
          _ (h/ok! (o/import-contribution! ctx (h/request ctx oid (mapv #(h/concept (str "urn:" %)) (range 100)))))
          input (assoc (h/request ctx oid [(assoc (h/concept "urn:new") :label " URN:27 ")])
                       :resolution-policy {:match-labels? true})
          candidates (atom nil) select idx/resolution-candidates]
      (with-redefs [idx/resolution-candidates (fn [& args]
                                              (let [result (apply select args)]
                                                (reset! candidates (mapv :uri result)) result))]
        (is (= "urn:27" (get-in (h/ok! (o/import-contribution! ctx input)) [:canonical-map "urn:new"]))))
      (is (= ["urn:27"] @candidates))
      (is (= 100 (count (o/get-concepts ctx {:ontology-id oid})))))))

(deftest connected-indexes-preserve-cross-ontology-cycle-validation
  (h/with-context [ctx]
    (let [a (h/graph ctx) b (h/graph ctx) c (h/graph ctx)
          link (fn [from to] (o/create-relationship! ctx {:source-ontology-id from :source-uri "urn:node"
                                                         :target-ontology-id to :target-uri "urn:node" :predicate "urn:parent"}))]
      (doseq [oid [a b c]]
        (h/add! ctx oid "urn:node")
        (h/ok! (o/register-predicate! ctx oid "urn:parent" {:hierarchical? true :cross-ontology? true})))
      (h/ok! (link a b)) (h/ok! (link b c))
      (check-indexes (graph-state ctx b))
      (let [before (h/graph-events ctx)]
        (is (h/anomaly? (link c a)))
        (is (= before (h/graph-events ctx)))))))

(deftest hierarchy-checks-only-new-connections
  (h/with-context [ctx]
    (let [oid (h/graph ctx) a (h/add! ctx oid "urn:a") _ (h/add! ctx oid "urn:b")
          _ (h/ok! (o/import-contribution! ctx (h/request ctx oid [(assoc (h/concept "urn:b") :broader ["urn:a"])])))
          check commands/validate-hierarchy-edge! calls (atom [])]
      (with-redefs [commands/validate-hierarchy-edge!
                    (fn [adjacency source target]
                      (swap! calls conj [source target]) (check adjacency source target))]
        (h/ok! (o/update-concept! ctx oid (:concept-id a) {:description "Changed"}))
        (h/ok! (o/import-contribution! ctx (h/request ctx oid [(assoc (h/concept "urn:b") :broader [])])))
        (is (empty? @calls))
        (h/ok! (o/import-contribution! ctx (h/request ctx oid [(assoc (h/concept "urn:a") :broader ["urn:b"])])))
        (is (= [[[oid "urn:a"] [oid "urn:b"]]] @calls))
        ;; Another hierarchical predicate between the same nodes adds no reachability.
        (h/ok! (o/create-relationship! ctx {:source-ontology-id oid :target-ontology-id oid
                                          :source-uri "urn:a" :target-uri "urn:b" :predicate "rdfs:subClassOf"}))
        (is (= 1 (count @calls)))))))

(deftest hierarchy-validation-uses-the-final-candidate
  (doseq [backend [:memory :sqlite]]
    (let [file (when (= backend :sqlite) (durable/temp-db))
          ctx (if file {:event-store (durable/open-store file) :tenant-id (random-uuid)} (h/context))]
      (try
        (let [oid (h/graph ctx)]
          (h/ok! (o/import-contribution! ctx (h/request ctx oid [(h/concept "urn:a")
                                                               (assoc (h/concept "urn:b") :broader ["urn:a"])])))
          ;; Reverse the edge atomically: the removed path must not reject the new edge.
          (is (not (h/anomaly? (o/import-contribution! ctx (h/request ctx oid
                                [(assoc (h/concept "urn:a") :broader ["urn:b"])
                                 (assoc (h/concept "urn:b") :broader [])])))))
          (let [before (h/graph-events ctx)]
            ;; A cycle consisting entirely of edges introduced in one contribution.
            (is (h/anomaly? (o/import-contribution! ctx (h/request ctx oid
                              [(assoc (h/concept "urn:x") :broader ["urn:y"])
                               (assoc (h/concept "urn:y") :broader ["urn:z"])
                               (assoc (h/concept "urn:z") :broader ["urn:x"])]))))
            (is (= before (h/graph-events ctx)))))
        (finally (es/stop (:event-store ctx)) (when file (durable/cleanup file)))))))

(defn acyclic-edges? [edges]
  ;; Independent whole-graph oracle: repeatedly remove nodes with no incoming edges.
  (loop [nodes (set (mapcat identity edges)) edges (set edges)]
    (if (empty? nodes) true
        (let [targets (set (map second edges)) roots (remove targets nodes)]
          (if (empty? roots) false
              (recur (apply disj nodes roots) (set (remove #(contains? (set roots) (first %)) edges))))))))

(deftest incremental-hierarchy-agrees-with-whole-graph-validation
  (let [oid (random-uuid)
        pairs (vec (for [a (range 6) b (range 6) :when (not= a b)] [a b]))
        generator (gen/tuple (gen/vector gen/boolean (count pairs)) (gen/vector gen/boolean (count pairs)))
        graph (fn [selected]
                (idx/rebuild
                 {:ontologies {oid {:predicate-policies {"p" {:hierarchical? true}}}}
                  :edges (into {} (map (fn [[a b]]
                                        (let [e (state/edge oid (str a) "p" (str b))]
                                          [(state/edge-key e) e]))) selected)}))
        result (tc/quick-check 500
                 (prop/for-all [[old-mask new-mask] generator]
                   (let [old (filter (fn [[a b]] (< a b)) (map first (filter second (map vector pairs old-mask))))
                         new (map first (filter second (map vector pairs new-mask)))
                         before (graph old) after (graph new)
                         e {:event/type :ontology/contribution-imported :ontology-id oid
                            :changes {:relationships (delta/records (:edges before) (:edges after))}}
                         accepted? (try (commands/validate-hierarchy! before after e) true
                                        (catch clojure.lang.ExceptionInfo _ false))]
                     (= (acyclic-edges? new) accepted?))) :seed 20260919)]
    (is (:pass? result) (pr-str result))))

(deftest newly-hierarchical-policy-is-validated
  (let [oid (random-uuid)
        before (idx/rebuild {:ontologies {oid {:predicate-policies {"p" {}}}}
                             :edges (into {} (map (fn [[a b]] (let [e (state/edge oid a "p" b)] [(state/edge-key e) e])))
                                          [["a" "b"] ["b" "a"]])})
        event {:event/type :ontology/predicate-registered :ontology-id oid
               :predicate "p" :policy {:hierarchical? true}}
        after (state/apply-event before event)]
    (is (thrown? clojure.lang.ExceptionInfo (commands/validate-hierarchy! before after event)))))

(deftest validation-visits-only-changed-records
  (h/with-context [ctx]
    (let [oid (h/graph ctx) root (h/add! ctx oid "urn:root")
          _ (h/ok! (o/import-contribution! ctx (h/request ctx oid
                    (mapv #(assoc (h/concept (str "urn:old" %)) :broader ["urn:root"]) (range 100)))))
          concepts (atom 0) edges (atom 0)
          check-c commands/validate-concept! check-e commands/validate-relationship!]
      (with-redefs [commands/validate-concept! (fn [c] (swap! concepts inc) (check-c c))
                    commands/validate-relationship! (fn [s e] (swap! edges inc) (check-e s e))]
        (h/ok! (o/import-contribution! ctx (h/request ctx oid
                  (mapv #(assoc (h/concept (str "urn:new" %)) :broader ["urn:root"]) (range 10)))))
        (is (= [10 10] [@concepts @edges]))
        (reset! concepts 0) (reset! edges 0)
        (h/ok! (o/update-concept! ctx oid (:concept-id root) {:description "Changed"}))
        (is (= [1 0] [@concepts @edges]))
        (reset! concepts 0)
        (h/ok! (o/create-relationship! ctx {:source-ontology-id oid :target-ontology-id oid
                                          :source-uri "urn:new0" :target-uri "urn:new1" :predicate "skos:related"}))
        (is (= [0 1] [@concepts @edges]))))))

(deftest endpoint-removal-and-policy-changes-recheck-dependent-edges
  (let [a (random-uuid) b (random-uuid)
        edge (assoc (state/edge a "urn:a" "p" "urn:b") :target-ontology-id b)
        key (state/edge-key edge)
        before (idx/rebuild {:ontologies {a {:predicate-policies {"p" {:cross-ontology? true}}} b {}}
                             :concepts {[a "urn:a"] (h/concept "urn:a") [b "urn:b"] (h/concept "urn:b")}
                             :edges {key edge}})
        removal {:event/type :ontology/contribution-imported :ontology-id b
                 :changes {:concepts {:removals [[b "urn:b"]]}}}
        after (state/apply-event before removal)]
    (is (= #{key} (get-in before [:indexes :concept-edges [b "urn:b"]])))
    (is (thrown? clojure.lang.ExceptionInfo (commands/validate-graph! after b before removal)))
    (let [event (assoc-in removal [:changes :relationships :removals] [key])
          candidate (state/apply-event before event)]
      (is (= candidate (commands/validate-graph! candidate b before event)))
      (is (empty? (get-in candidate [:indexes :concept-edges]))))
    (let [event {:event/type :ontology/predicate-registered :ontology-id a
                 :predicate "p" :policy {:cross-ontology? false}}
          candidate (state/apply-event before event)]
      (is (thrown? clojure.lang.ExceptionInfo (commands/validate-graph! candidate a before event))))))

(deftest changed-record-validation-remains-atomic-on-both-stores
  (doseq [backend [:memory :sqlite]]
    (let [file (when (= backend :sqlite) (durable/temp-db))
          ctx (if file {:event-store (durable/open-store file) :tenant-id (random-uuid)} (h/context))]
      (try
        (let [oid (h/graph ctx)]
          (h/ok! (o/import-contribution! ctx (h/request ctx oid [(h/concept "urn:root")
                            (assoc (h/concept "urn:child") :broader ["urn:root"])])))
          (doseq [records [[(assoc (h/concept "urn:child") :label " ")]
                           [(assoc (h/concept "urn:child") :broader ["urn:missing"])]
                           [(assoc (h/concept "urn:child") :broader ["urn:child"])]]]
            (let [before (h/capture ctx oid)]
              (is (h/anomaly? (o/import-contribution! ctx (h/request ctx oid records))))
              (is (= before (h/capture ctx oid))))))
        (finally (es/stop (:event-store ctx)) (when file (durable/cleanup file)))))))

(deftest incremental-record-validation-agrees-with-full-validation
  (let [oid (random-uuid)
        pairs (vec (for [a (range 5) b (range 5)] [a b]))
        generator (gen/tuple (gen/vector gen/boolean 5) (gen/vector gen/boolean 5)
                             (gen/set (gen/elements pairs) {:max-elements 8}))
        before (idx/rebuild {:ontologies {oid {:predicate-policies {"p" {}}}}
                             :concepts (into {} (for [i (range 5)] [[oid (str i)] (h/concept (str i))]))})
        result (tc/quick-check 500
                 (prop/for-all [[present valid pairs] generator]
                   (let [candidate (assoc before
                                     :concepts (into {} (for [i (range 5) :when (nth present i)]
                                                          [[oid (str i)] (assoc (h/concept (str i)) :description (if (nth valid i) "valid" " "))]))
                                     :edges (into {} (for [[a b] pairs :let [e (state/edge oid (str a) "p" (str b))]]
                                                      [(state/edge-key e) e])))
                         event {:event/type :ontology/contribution-imported :ontology-id oid
                                :changes (delta/changes before candidate oid)}
                         after (state/apply-event before event)
                         expected (and (every? #(not (clojure.string/blank? (:description %))) (vals (:concepts candidate)))
                                       (every? (fn [[a b]] (and (not= a b) (nth present a) (nth present b))) pairs))
                         actual (try (commands/validate-graph! after oid before event) true
                                     (catch clojure.lang.ExceptionInfo _ false))]
                     (= (boolean expected) actual))) :seed 20260920)]
    (is (:pass? result) (pr-str result))))
