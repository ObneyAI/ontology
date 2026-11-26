(ns ai.obney.ontology.grain-test
  (:require [ai.obney.grain.event-store-v3.interface :as es]
            [ai.obney.ontology.grain :as grain]
            [ai.obney.ontology.interface :as graph]
            [ai.obney.ontology.support :as h]
            [clojure.test :refer [deftest is]]))

(defn- commit! [ctx plan]
  (es/append (:event-store ctx) {:tenant-id (:tenant-id ctx)
                                 :events (:command-result/events plan)
                                 :cas (:command-result/cas plan)}))

(deftest prepare-vocabulary-validates-and-commits-with-host
  (h/with-context [ctx]
    (let [definition {:name "Task families" :request-id (random-uuid)}
          request {:ontology definition
                   :concepts [(h/concept "parent") (assoc (h/concept "child") :embedding [1.0] :embedding-model "fixture")]
                   :relationships [{:source-uri "child" :predicate "skos:broader" :target-uri "parent"}]}
          prepare #(grain/prepare-vocabulary ctx request)
          stale (prepare)
          graph-id (:ontology-id (h/ok! (graph/create-ontology! ctx definition)))]
      (is (empty? (graph/get-concepts ctx)))
      (is (= :cognitect.anomalies/conflict (:cognitect.anomalies/category (commit! ctx stale))))
      (let [plan (prepare)]
        (is (= graph-id (get-in plan [:command-result/data :ontology-id])))
        (is (= 3 (count (:command-result/events plan))))
        (is (empty? (graph/get-concepts ctx)))
        (h/ok! (graph/create-ontology! ctx {:name "Independent graph"}))
        (h/ok! (commit! ctx plan))
        (is (= #{"child"} (:narrower (graph/get-concept-by-uri ctx graph-id "parent"))))
        (let [host-ctx (assoc ctx :command {:command/name :host/inspect})]
          (is (= 2 (count (graph/get-concepts host-ctx {:ontology-id graph-id}))))
          (is (= graph-id (:ontology-id (graph/export-snapshot host-ctx graph-id)))))
        (is (= [1.0] (:embedding (graph/get-concept-by-uri ctx graph-id "child"))))
        (is (empty? (:command-result/events (prepare))))
        (is (= [] (:command-result/events (grain/prepare-vocabulary ctx
                            (assoc request :relationships [{:source-uri "parent" :predicate "skos:narrower"
                                                            :target-uri "child"}]))))))
      (let [failed (grain/prepare-vocabulary ctx
                     (update request :relationships conj {:source-uri "parent" :predicate "skos:broader" :target-uri "child"}))]
        (is (h/anomaly? failed))
        (is (= 4 (graph/get-revision ctx graph-id))))
      (is (h/anomaly? (grain/prepare-vocabulary ctx (assoc-in request [:ontology :name] "Another payload"))))
      (is (h/anomaly? (grain/prepare-vocabulary (dissoc ctx :tenant-id) request))))))
