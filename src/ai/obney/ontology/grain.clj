(ns ai.obney.ontology.grain
  "Compose validated graph writes with a host command's events in one Grain append."
  (:require [ai.obney.ontology.core.commands :as commands]
            [ai.obney.ontology.core.errors :as errors]
            [ai.obney.ontology.core.state :as state]
            [ai.obney.ontology.core.api-schemas :as api]
            [malli.core :as m]))

(def ^:private vocabulary
  [:map {:closed true}
   [:ontology [:map {:closed true} [:request-id :uuid] [:name ::api/nonblank-string]
               [:description {:optional true} :string] [:base-uri {:optional true} :string]
               [:scope {:optional true} ::api/scope] [:predicate-policies {:optional true} ::api/policies]]]
   [:concepts [:vector ::api/concept-definition]]
   [:relationships [:vector [:map {:closed true} [:source-uri ::api/nonblank-string]
                             [:target-uri ::api/nonblank-string] [:predicate ::api/nonblank-string]
                             [:metadata {:optional true} :any]]]]])

(defn- stage [batch oid command-name request-id params change]
  (let [command (assoc params :command/name command-name)
        result (commands/prepare-change (:state batch) command oid request-id nil (change params))]
    (when (:cognitect.anomalies/category result)
      (throw (ex-info (:cognitect.anomalies/message result) result)))
    (let [events (:command-result/events result)]
      (-> batch
          (update :state #(reduce state/apply-event % events))
          (update :events into events)
          (assoc :data (:command-result/data result))))))

(defn prepare-vocabulary
  "Prepare an idempotent graph vocabulary for a Grain command. Supply an :ontology
   create request with a stable :request-id, a vector of :concepts, and a vector of
   :relationships with :source-uri, :predicate and :target-uri in that graph.
   Existing concepts and edges are retained. Put new parents before their children
   or supply hierarchy edges separately. Returns a Grain command result or anomaly.
   The host must append the returned events AND honor the CAS in the same commit."
  [ctx {:keys [ontology concepts relationships] :as request}]
  (errors/call ctx
    (fn []
      (let [key (:request-id ontology)
            params (dissoc ontology :request-id)
            _ (errors/require! (m/validate vocabulary request) "Invalid vocabulary request")
            ctx (dissoc ctx :command :ontology/ids)
            prior (state/read-request ctx nil key)
            existing-id (get-in prior [:result :ontology-id])
            initial (when existing-id (state/read-state (assoc ctx :command {:ontology-id existing-id})))
            graph (stage {:state initial :events []} nil :ontology/create-ontology key params commands/create-ontology-change)
            oid (get-in graph [:data :ontology-id])
            nodes (reduce (fn [batch c]
                            (if (get-in batch [:state :concepts [oid (:uri c)]]) batch
                                (stage batch oid :ontology/create-concept (random-uuid)
                                       (assoc c :ontology-id oid) commands/create-concept-change)))
                          graph concepts)
            complete (reduce (fn [batch edge]
                               (let [edge (assoc edge :source-ontology-id oid :target-ontology-id oid)]
                                 (if (some #(= (state/edge-key edge) (state/edge-key %))
                                           (state/relationships (:state batch) oid)) batch
                                     (stage batch oid :ontology/create-relationship (random-uuid)
                                            edge commands/create-relationship-change))))
                             nodes relationships)]
        {:command-result/events (:events complete)
         :command-result/data {:ontology-id oid}
         :command-result/cas (commands/mutation-guard initial existing-id key)}))))
