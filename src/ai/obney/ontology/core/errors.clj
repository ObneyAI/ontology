(ns ai.obney.ontology.core.errors
  "Expected failures cross the public boundary as Cognitect anomaly maps."
  (:require [cognitect.anomalies :as anom]))

(defn fail! [category message]
  (throw (ex-info message {::anom/category category ::anom/message message})))

(defn require! [condition message]
  (when-not condition (fail! ::anom/incorrect message)))

(defn call [ctx f]
  (try
    (require! (map? ctx) "Context must be a map")
    (when-not (:tenant-id ctx) (fail! ::anom/forbidden "A tenant-id is required"))
    (require! (some? (:event-store ctx)) "An event-store is required")
    (f)
    (catch clojure.lang.ExceptionInfo e
      (if (::anom/category (ex-data e)) (ex-data e) (throw e)))))
