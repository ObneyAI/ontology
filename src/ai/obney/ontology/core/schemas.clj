(ns ai.obney.ontology.core.schemas
  (:require [ai.obney.grain.schema-util.interface :refer [defschemas]]))

(def scope [:or :keyword :string])
(def policy [:map {:closed true}
                              [:symmetric? {:optional true} :boolean]
                              [:cross-ontology? {:optional true} :boolean]
                              [:hierarchical? {:optional true} :boolean]
                              [:inverse {:optional true} :string]])
(def policies [:map-of :string policy])
(def concept [:map [:uri :string] [:label :string] [:description :string]
              [:scope {:optional true} scope]
              [:broader {:optional true} [:vector :string]]
              [:indicators {:optional true} [:vector :string]]
              [:attributes {:optional true} :map]
              [:entity-type {:optional true} :string]
              [:alt-labels {:optional true} [:vector :string]]
              [:provenance {:optional true} :map]])

(def changes [:map
              [:label {:optional true} :string]
              [:description {:optional true} :string]
              [:broader {:optional true} [:vector :string]]
              [:indicators {:optional true} [:vector :string]]
              [:attributes {:optional true} :map]])

(defschemas commands
  {:ontology/create-ontology [:map [:name :string] [:scope {:optional true} scope]
                              [:predicate-policies {:optional true} policies]]
   :ontology/create-concept [:and concept [:map [:ontology-id :uuid]]]
   :ontology/update-concept [:map [:ontology-id :uuid] [:concept-id :uuid] [:changes changes]]
   :ontology/create-relationship [:map [:source-ontology-id :uuid] [:target-ontology-id :uuid]
                                 [:source-uri :string] [:target-uri :string] [:predicate :string]]
   :ontology/register-predicate [:map [:ontology-id :uuid] [:predicate :string] [:policy policy]]
   :ontology/import-contribution [:map [:ontology-id :uuid] [:contribution-id :uuid]
                                 [:expected-revision :int]
                                 [:source [:map [:source-id :uuid] [:content-hash :string]
                                           [:producer {:optional true} :map]]]
                                 [:concepts [:vector concept]]
                                 [:relationships {:optional true} [:vector :map]]
                                 [:tbox {:optional true} :map]
                                 [:abox {:optional true} [:vector :map]]]})

(defn record-delta [key-schema value-schema]
  [:map [:upserts [:map-of key-schema value-schema]] [:removals [:vector key-schema]]])
(def concept-key [:tuple :uuid :string])
(def relationship-key [:tuple :uuid :string :string :uuid :string])
(def contribution-changes
  [:map
   [:concepts (record-delta concept-key :map)]
   [:relationships (record-delta relationship-key :map)]
   [:tbox [:map-of :keyword (record-delta :string :map)]]
   [:tbox-removals [:vector :keyword]]
   [:abox (record-delta :string :map)]
   [:rdf-triples [:map [:additions [:vector :map]] [:removals [:vector :map]]]]])

(defschemas events
  {:ontology/ontology-created [:map [:ontology-id :uuid] [:name :string] [:scope {:optional true} scope]
                               [:predicate-policies {:optional true} policies] [:created-at :string]]
   :ontology/concept-created [:map [:ontology-id :uuid] [:concept-id :uuid]
                              [:uri :string] [:label :string] [:description :string]
                              [:scope {:optional true} scope] [:created-at :string]]
   :ontology/concept-updated [:map [:ontology-id :uuid] [:concept-id :uuid]
                              [:changes :map] [:updated-at :string]]
   :ontology/relationship-created [:map [:source-ontology-id :uuid] [:target-ontology-id :uuid]
                                   [:source-uri :string] [:target-uri :string] [:predicate :string]]
   :ontology/predicate-registered [:map [:ontology-id :uuid] [:predicate :string] [:policy :map]]
   :ontology/contribution-imported [:map [:ontology-id :uuid] [:contribution-id :uuid]
                                    [:source :map] [:changes contribution-changes]
                                    [:canonical-map :map] [:result :map] [:request-hash :string]]})
