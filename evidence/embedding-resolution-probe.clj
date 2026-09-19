(require '[ai.obney.grain.event-store-v3.interface :as es]
         '[ai.obney.ontology.interface :as o])
(let [store (es/start {:conn {:type :in-memory} :event-pubsub nil :logger nil})
      ctx {:event-store store :tenant-id (random-uuid)}]
  (try
    (doseq [[model expected] [["m1" 1] ["m2" 2] [nil 2]]]
      (let [oid (:ontology-id (o/create-ontology! ctx {:name "Embeddings"}))]
        (o/create-concept! ctx oid {:uri "urn:old" :label "old name" :description "old"
                                   :embedding [1.0 0.0] :embedding-model "m1"})
        (let [result (o/import-contribution! ctx
                       {:ontology-id oid :contribution-id (random-uuid)
                        :expected-revision (o/get-revision ctx oid)
                        :source {:source-id (random-uuid) :content-hash "abc"}
                        :resolution-policy {:match-labels? true}
                        :concepts [{:uri "urn:new" :label "new name" :description "new"
                                    :embedding [1.0 0.0] :embedding-model model}]})]
          (assert (not (:cognitect.anomalies/category result)) result)
          (assert (= expected (count (o/get-concepts ctx {:ontology-id oid})))
                  (str "Unexpected resolution for model " model)))))
    (println "Public import embedding model cases passed: matching, different, absent.")
    (finally (es/stop store))))
(shutdown-agents)
