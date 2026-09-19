(ns ai.obney.ontology.delta-test
  (:require [clojure.test :refer [deftest is]]
            [clojure.test.check :as tc]
            [clojure.test.check.generators :as gen]
            [clojure.test.check.properties :as prop]
            [ai.obney.ontology.core.delta :as d]
            [ai.obney.ontology.core.indexes :as idx]
            [ai.obney.ontology.core.state :as state]
            [ai.obney.ontology.core.streams :as streams]
            [ai.obney.ontology.interface :as o]
            [ai.obney.ontology.support :as h]))

(deftest resolved-record-diffs-reconstruct-candidates
  (let [oid (random-uuid)
        values (gen/map (gen/choose 0 12) (gen/tuple gen/boolean (gen/choose 0 50)))
        graph (fn [values]
                (let [records (mapv (fn [[n [flag value]]]
                                      (cond-> {:uri (str "urn:" n) :label (str value)} flag (assoc :optional value)))
                                    (sort-by key values))]
                  {:concepts (into {} (map (fn [c] [[oid (:uri c)] c])) records)
                   :edges (into {} (for [c records :let [e (assoc (state/edge oid (:uri c) "urn:p" "urn:target") :metadata (:label c))]]
                                    [(state/edge-key e) e]))
                   :tbox {oid (if (even? (count records)) {} {:classes (vec (sort-by :uri records))})} :abox {oid (vec (sort-by :uri records))}
                   :rdf-triples {oid (vec (sort-by pr-str (map #(select-keys % [:uri :label]) records)))}}))
        result (tc/quick-check 500
                 (prop/for-all [a values b values]
                   (let [before (graph a) candidate (graph b) changes (d/changes before candidate oid)]
                     (= candidate (d/apply-changes before oid changes)))) :seed 20260918)]
    (is (:pass? result) (pr-str result))))

(deftest a-small-contribution-does-not-carry-the-existing-graph
  (h/with-context [ctx]
    (let [oid (h/graph ctx)
          _ (h/ok! (o/import-contribution! ctx oid (h/request ctx oid (mapv #(h/concept (str "urn:" %)) (range 1000)))))
          before (state/read-state (assoc ctx :ontology/ids #{oid}))
          input (h/request ctx oid [(assoc (h/concept "urn:1") :label "Changed")])
          result (h/ok! (o/import-contribution! ctx oid input))
          event (last (h/graph-events ctx)) changes (:changes event)
          after (state/read-state (assoc ctx :ontology/ids #{oid}))]
      (is (= #{[oid "urn:1"]} (set (keys (get-in changes [:concepts :upserts])))))
      (is (empty? (get-in changes [:relationships :upserts])))
      (is (not-any? #(contains? event %) [:concepts :relationships :tbox :abox :rdf-triples]))
      (is (< (count (.getBytes (pr-str event) "UTF-8")) 6000))
      (is (= 1000 (:concept-count result)))
      (is (= (select-keys after [:concepts :edges :tbox :abox :rdf-triples])
             (select-keys (d/apply-changes before oid changes) [:concepts :edges :tbox :abox :rdf-triples])))
      (is (= "Changed" (:label (o/get-concept-by-uri ctx oid "urn:1"))))
      (is (= result (o/import-contribution! ctx oid input)))
      (h/with-context [fresh]
        (h/replay! ctx fresh)
        (is (= (o/export-snapshot ctx oid) (o/export-snapshot fresh oid)))
        (is (= (o/get-contributions ctx oid) (o/get-contributions fresh oid)))))))

(deftest contribution-parent-diffs-update-both-endpoints-and-history
  (h/with-context [ctx]
    (let [oid (h/graph ctx) _ (h/add! ctx oid "urn:a") _ (h/add! ctx oid "urn:b")
          _ (h/ok! (o/import-contribution! ctx oid (h/request ctx oid [(assoc (h/concept "urn:child") :broader ["urn:a"])])))
          snapshot (o/export-snapshot ctx oid) revision (:revision snapshot)]
      (o/get-concept-by-uri ctx oid "urn:a")
      (o/get-concept-by-uri ctx oid "urn:b")
      (o/get-concept-by-uri ctx oid "urn:child")
      (h/ok! (o/import-contribution! ctx oid (h/request ctx oid [(assoc (h/concept "urn:child") :broader ["urn:b"])])))
      (let [event (last (h/graph-events ctx))]
        (is (= [(state/edge-key (state/edge oid "urn:child" "skos:broader" "urn:a"))]
               (get-in event [:changes :relationships :removals])))
        (is (= 1 (count (get-in event [:changes :relationships :upserts]))))
        (is (every? (:event/tags event) (map #(streams/concept-tag oid %) ["urn:a" "urn:b" "urn:child"]))))
      (is (= #{} (:narrower (o/get-concept-by-uri ctx oid "urn:a"))))
      (is (= #{"urn:child"} (:narrower (o/get-concept-by-uri ctx oid "urn:b"))))
      (is (= #{"urn:b"} (:broader (o/get-concept-by-uri ctx oid "urn:child"))))
      (is (= snapshot (o/export-snapshot ctx oid revision)))
      (is (= 2 (count (o/get-relationships ctx oid)))))))

(deftest empty-graph-change-still-records-contribution-acceptance
  (h/with-context [ctx]
    (let [oid (h/graph ctx) _ (h/add! ctx oid "urn:existing")
          before (o/export-snapshot ctx oid) input (h/request ctx oid [])
          result (h/ok! (o/import-contribution! ctx oid input)) event (last (h/graph-events ctx))]
      (is (= (inc (:revision before)) (:revision result)))
      (is (every? empty? (vals (get-in event [:changes :concepts]))))
      (is (= (:concepts before) (:concepts (o/export-snapshot ctx oid))))
      (is (= 1 (count (o/get-contributions ctx oid))))
      (is (= 1 (count (o/get-sources ctx oid))))
      (is (= result (o/import-contribution! ctx oid input))))))

(deftest touched-records-report-net-changes-only
  (let [oid (random-uuid)
        before (-> (idx/rebuild {})
                   (state/put-concept oid (h/concept "urn:a"))
                   (state/put-concept oid (h/concept "urn:b"))
                   (state/put-concept oid (assoc (h/concept "urn:child") :broader ["urn:a"])))
        key (state/edge-key (state/edge oid "urn:child" "skos:broader" "urn:a"))
        restored (-> (d/track before)
                     (state/put-concept oid {:uri "urn:child" :label "temporary"})
                     (state/put-concept oid {:uri "urn:child" :label "urn:child" :broader ["urn:a"]}))
        changes (d/changes before restored oid)]
    (is (= #{[oid "urn:child"]} (get-in restored [::d/touched :concepts])))
    (is (= #{key} (get-in restored [::d/touched :edges])))
    (is (= {:upserts {} :removals []} (:concepts changes)))
    (is (= {:upserts {} :removals []} (:relationships changes)))
    (let [after (state/put-concept restored oid {:uri "urn:child" :broader ["urn:b"]})
          changes (d/changes before after oid)]
      (is (= [key] (get-in changes [:relationships :removals])))
      (is (= #{(state/edge-key (state/edge oid "urn:child" "skos:broader" "urn:b"))}
             (set (keys (get-in changes [:relationships :upserts]))))))))

(deftest tracked-mutation-sequences-agree-with-full-diffs
  (let [oid (random-uuid)
        before (reduce #(state/put-concept %1 oid (h/concept (str %2)))
                       (idx/rebuild {:ontologies {oid {:predicate-policies state/default-predicates}}}) (range 6))
        operation (gen/tuple (gen/elements [:concept :parents :edge :remove])
                             (gen/choose 0 5) (gen/choose 0 5))
        result (tc/quick-check 500
                 (prop/for-all [operations (gen/vector operation 0 40)]
                   (let [after (reduce (fn [s [op a b]]
                                         (let [a (str a) b (str b) edge (state/edge oid a "skos:narrower" b)]
                                           (case op
                                             :concept (state/put-concept s oid {:uri a :label b})
                                             :parents (state/put-concept s oid {:uri a :broader [b]})
                                             :edge (state/put-edge s (assoc edge :metadata {:value b}))
                                             :remove (state/remove-edge s (state/edge-key (state/canonical-edge edge))))))
                                       (d/track before) operations)
                         scoped (d/changes before after oid)
                         full (d/changes before (dissoc after ::d/touched) oid)
                         replay (d/apply-changes before oid scoped)]
                     (and (= full scoped)
                          (= (map #(or (get after %) {}) [:concepts :edges :indexes])
                             (map #(or (get replay %) {}) [:concepts :edges :indexes]))))) :seed 20260921)]
    (is (:pass? result) (pr-str result))))

(deftest imports-diff-only-touched-identities-and-do-not-persist-tracking
  (h/with-context [ctx]
    (let [oid (h/graph ctx)
          _ (h/ok! (o/import-contribution! ctx oid (h/request ctx oid
                    (mapv #(h/concept (str "urn:old" %)) (range 100)))))
          records d/records sizes (atom [])
          input (h/request ctx oid (mapv #(assoc (h/concept (str "urn:new" %)) :broader ["urn:old0"]) (range 10)))]
      (with-redefs [d/records (fn [before after]
                               (swap! sizes conj [(count before) (count after)])
                               (records before after))]
        (h/ok! (o/import-contribution! ctx oid input)))
      (is (= [[0 10] [0 10]] (vec (take 2 @sizes))))
      (is (every? #(every? (fn [n] (<= n 10)) %) @sizes))
      (is (not-any? #(and (map? %) (contains? % ::d/touched))
                    (tree-seq coll? seq (last (h/graph-events ctx)))))
      (is (not (contains? (state/read-state (assoc ctx :ontology/ids #{oid})) ::d/touched))))))
