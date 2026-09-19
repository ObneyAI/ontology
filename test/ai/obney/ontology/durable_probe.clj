(ns ai.obney.ontology.durable-probe
  "Executed in a fresh JVM by the durable suite; no inherited application state."
  (:require [ai.obney.ontology.interface :as o]
            [ai.obney.grain.event-store-v3.interface :as es]
            [ai.obney.grain.event-store-sqlite-v3.interface]))
(defn -main [file tenant oid]
  (let [store (es/start {:conn {:type :sqlite :database-file file}})
        ctx {:event-store store :tenant-id (parse-uuid tenant)} id (parse-uuid oid)]
    (try (prn {:snapshot (o/export-snapshot ctx id)
               :sources (o/get-sources ctx id) :contributions (o/get-contributions ctx id)})
         (finally (es/stop store) (shutdown-agents)))))
