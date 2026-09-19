(ns ai.obney.ontology.support
  (:require [ai.obney.ontology.interface :as o]
            [ai.obney.grain.event-store-v3.interface :as es]
            [ai.obney.grain.command-processor-v2.interface :as cp]
            [ai.obney.grain.time.interface :as time]))

(defn anomaly? [x] (some? (:cognitect.anomalies/category x)))
(defn ok! [x] (when (anomaly? x) (throw (ex-info "Fixture command failed" x))) x)
(defn context [] {:event-store (es/start {:conn {:type :in-memory} :event-pubsub nil :logger nil})
                  :tenant-id (random-uuid)})
(defmacro with-context [[ctx] & body]
  `(let [~ctx (context)] (try ~@body (finally (es/stop (:event-store ~ctx))))))
(defn graph [ctx] (:ontology-id (ok! (o/create-ontology! ctx {:name "Fixture" :scope :domain :base-uri "urn:fixture:"}))))
(defn concept [uri] {:uri uri :label uri :description (str "Description " uri) :scope :domain})
(defn add! [ctx oid uri] (ok! (o/create-concept! ctx oid (concept uri))))
(defn request [ctx oid concepts]
  {:contribution-id (random-uuid) :expected-revision (o/get-revision ctx oid)
   :source {:source-id (random-uuid) :content-hash "fixture"} :concepts concepts})
(defn graph-events [ctx]
  (into [] (es/read (:event-store ctx) {:tenant-id (:tenant-id ctx) :types o/graph-event-types})))
(defn send! [ctx name params id]
  (let [r (cp/process-command (assoc ctx :command (merge params {:command/name name :command/id id
                                                               :command/timestamp (time/now)})))]
    (if (anomaly? r) r (:command-result/data r))))
(defn capture [ctx oid]
  {:snapshot (o/export-snapshot ctx oid) :sources (o/get-sources ctx oid)
   :contributions (o/get-contributions ctx oid) :events (graph-events ctx)})
(defn rejected? [f]
  (try (anomaly? (f)) (catch clojure.lang.ExceptionInfo _ true)))
(defn replay! [ctx target]
  (ok! (es/append (:event-store target)
                  {:tenant-id (:tenant-id target)
                   :events (mapv #(dissoc % :event/id :event/timestamp) (graph-events ctx))})))
