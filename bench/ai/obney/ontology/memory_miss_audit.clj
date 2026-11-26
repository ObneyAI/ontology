(ns ai.obney.ontology.memory-miss-audit
  (:require [ai.obney.grain.event-store-v3.interface :as es]
            [ai.obney.ontology.interface :as o]
            [ai.obney.ontology.memory-pilot :as pilot]
            [cheshire.core :as json]
            [clojure.java.io :as io]
            [clojure.string :as str]))

(defn- read-json [file] (json/parse-string (slurp file) true))

(defn- check-case [root {:keys [id turns]}]
  (let [store (es/start {:conn {:type :in-memory} :event-pubsub nil :logger nil})
        ctx {:event-store store :tenant-id (random-uuid)}]
    (try
      (let [graph (:ontology-id (o/create-ontology! ctx {:name "Miss replay"}))
            input (read-json (io/file root id "input.json"))
            sessions (partition-by :session (remove #(str/blank? (:text %)) (:turns input)))
            by-id (into {} (map (juxt :id identity)) (mapcat identity sessions))
            uri #(str "urn:longmem:" %)]
        (doseq [session sessions] (pilot/import-session! ctx graph session))
        (doseq [{key :turn} turns]
          (let [source (by-id key)
                hit (o/get-concept-by-uri ctx graph (uri key))
                session (filterv #(= (:session source) (:session %)) (mapcat identity sessions))
                ids (mapv :id session)
                i (.indexOf ids key)
                expected (set (subvec ids (max 0 (dec i)) (min (count ids) (+ i 2))))
                actual (o/search ctx graph {:seed-uris [(uri key)] :max-depth 1 :limit 10})]
            (when-not (and (= (:description hit) (:text source))
                           (= (:attributes hit) (select-keys source [:session :date :id :role]))
                           (= expected (set (map #(get-in % [:attributes :id]) actual))))
              (throw (ex-info "Source round-trip or adjacent edges differ" {:case id :turn key})))))
        {:id id :verified-turns (count turns)})
      (finally (es/stop store)))))

(defn -main [root audit-file]
  (try
    (doseq [miss (:complete_miss_traces (read-json audit-file))]
      (println (json/generate-string (check-case root miss))))
    (finally (shutdown-agents))))
