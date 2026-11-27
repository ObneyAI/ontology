(ns ai.obney.ontology.core.schemas
  "Registered storage, request and public function schemas for the ontology library."
  (:require [ai.obney.grain.schema-util.interface :refer [defschemas]]
            [ai.obney.grain.event-store-v3.interface.protocol :as event-store]
            [ai.obney.ontology.core.api-schemas :as-alias api]
            [ai.obney.ontology.core.rdf :as rdf]
            [clojure.string :as str]
            [malli.util :as mu]))

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
              [:scope {:optional true} scope]
              [:alt-labels {:optional true} [:vector :string]]
              [:provenance {:optional true} :map]
              [:entity-type {:optional true} :string]
              [:label {:optional true} :string]
              [:description {:optional true} :string]
              [:broader {:optional true} [:vector :string]]
              [:indicators {:optional true} [:vector :string]]
              [:attributes {:optional true} :map]])

(defschemas commands
  {:ontology/create-ontology [:map [:name :string] [:scope {:optional true} scope]
                              [:predicate-policies {:optional true} policies]]
   :ontology/create-concept [:and concept [:map [:ontology-id :uuid]]]
   :ontology/delete-concept [:map [:ontology-id :uuid] [:concept-id :uuid]]
   :ontology/rename-concept [:map [:ontology-id :uuid] [:concept-id :uuid] [:new-uri :string]]
   :ontology/merge-concepts [:map [:ontology-id :uuid] [:concept-id :uuid] [:target-concept-id :uuid]]
   :ontology/update-concept [:map [:ontology-id :uuid] [:concept-id :uuid] [:changes changes]]
   :ontology/create-relationship [:map [:source-ontology-id :uuid] [:target-ontology-id :uuid]
                                 [:source-uri :string] [:target-uri :string] [:predicate :string]]
   :ontology/register-predicate [:map [:ontology-id :uuid] [:predicate :string] [:policy policy]]
   :ontology/import-contribution [:map [:ontology-id :uuid] [:contribution-id :uuid]
                                 [:expected-revision {:optional true} :int]
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

(def lifecycle-event
  [:map [:ontology-id :uuid] [:old-uri :string] [:new-uri [:maybe :string]]
   [:changes contribution-changes] [:affected-ontology-ids [:vector :uuid]]])

(defschemas events
  {:ontology/ontology-created [:map [:ontology-id :uuid] [:name :string] [:scope {:optional true} scope]
                               [:predicate-policies {:optional true} policies] [:created-at :string]]
   :ontology/concept-created [:map [:ontology-id :uuid] [:concept-id :uuid]
                              [:uri :string] [:label :string] [:description :string]
                              [:scope {:optional true} scope] [:created-at :string]]
   :ontology/concept-deleted lifecycle-event
   :ontology/concept-renamed lifecycle-event
   :ontology/concepts-merged lifecycle-event
   :ontology/concept-updated [:map [:ontology-id :uuid] [:concept-id :uuid]
                              [:changes :map] [:updated-at :string]]
   :ontology/relationship-created [:map [:source-ontology-id :uuid] [:target-ontology-id :uuid]
                                   [:source-uri :string] [:target-uri :string] [:predicate :string]]
   :ontology/predicate-registered [:map [:ontology-id :uuid] [:predicate :string] [:policy :map]]
   :ontology/contribution-imported [:map [:ontology-id :uuid] [:contribution-id :uuid]
                                    [:source :map] [:changes contribution-changes]
                                    [:canonical-map :map] [:result :map] [:request-hash :string]]})

(def ^:private mutable-fields
  #{:label :description :broader :indicators :attributes :embedding :embedding-model
    :scope :alt-labels :provenance :entity-type})

(defschemas common
  {::api/embedding [:maybe sequential?]
   ::api/embedding-model [:maybe [:or :string :keyword]]
   ::api/relationship-metadata [:maybe :map]

   ::api/nonblank-string
   [:and :string [:fn {:error/message "must not be blank"} #(not (str/blank? %))]]

   ::api/ontology-id
   [:or :uuid ::api/nonblank-string]

   ::api/context
   [:map
    [:event-store [:fn {:error/message "must implement Grain EventStore"}
                   #(satisfies? event-store/EventStore %)]]
    [:tenant-id :uuid]]

   ::api/anomaly
   [:map
    [:cognitect.anomalies/category :qualified-keyword]
    [:cognitect.anomalies/message {:optional true} :string]]

   ::api/revision
   [:int {:min 1}]

   ::api/scope
   scope

   ::api/policy
   policy

   ::api/policies
   policies

   ::api/command-options
   [:map [:request-id {:optional true} :uuid] [:expected-revision {:optional true} :int]]})

(defschemas fields
  {::api/concept-definition
   (mu/merge concept
             [:map [:uri ::api/nonblank-string] [:label ::api/nonblank-string] [:description ::api/nonblank-string]
              [:embedding {:optional true} ::api/embedding] [:embedding-model {:optional true} ::api/embedding-model]])

   ::api/changes
   [:and
    (mu/merge changes
              [:map [:label {:optional true} ::api/nonblank-string]
               [:description {:optional true} ::api/nonblank-string]
               [:embedding {:optional true} ::api/embedding] [:embedding-model {:optional true} ::api/embedding-model]])
    [:fn {:error/message "must supply at least one mutable field"}
     #(some (partial contains? %) mutable-fields)]]

   ::api/edge
   [:map [:source-ontology-id :uuid] [:source-uri ::api/nonblank-string]
    [:target-ontology-id :uuid] [:target-uri ::api/nonblank-string] [:predicate ::api/nonblank-string]
    [:metadata {:optional true} ::api/relationship-metadata]]

   ::api/source
   [:map [:source-id :uuid] [:content-hash ::api/nonblank-string] [:producer {:optional true} :map]]

   ::api/resolution-policy
   [:map [:match-labels? {:optional true} :boolean]
    [:similarity-threshold {:optional true}
     [:and number? [:fn {:error/message "must be finite and between 0 and 1"}
                    #(and (Double/isFinite (double %)) (<= 0 % 1))]]]]

   ::api/local-edge
   [:and
    [:map [:predicate ::api/nonblank-string]
     [:subject {:optional true} ::api/nonblank-string] [:object {:optional true} ::api/nonblank-string]
     [:source-uri {:optional true} ::api/nonblank-string] [:target-uri {:optional true} ::api/nonblank-string]
     [:source-ontology-id {:optional true} :uuid] [:target-ontology-id {:optional true} :uuid]
     [:metadata {:optional true} ::api/relationship-metadata]]
    [:fn {:error/message "must supply source and target URIs"}
     #(and (or (:subject %) (:source-uri %)) (or (:object %) (:target-uri %)))]]

   ::api/schema-record
   [:map [:uri ::api/nonblank-string] [:label {:optional true} :string]
    [:domain {:optional true} ::api/nonblank-string] [:range {:optional true} ::api/nonblank-string]
    [:datatype {:optional true} ::api/nonblank-string]]

   ::api/individual
   [:map [:uri ::api/nonblank-string] [:type {:optional true} ::api/nonblank-string]
    [:properties {:optional true} :map]]

   ::api/rdf-triple
   [:and
    [:map [:subject ::api/nonblank-string] [:predicate ::api/nonblank-string]
     [:object-uri {:optional true} [:maybe ::api/nonblank-string]]
     [:literal {:optional true} [:maybe :string]]
     [:language {:optional true} [:maybe :string]]
     [:datatype {:optional true} [:maybe ::api/nonblank-string]]]
    [:fn {:error/message "must have a URI object or literal with optional language or datatype"}
     #(if (some? (:object-uri %))
        (every? nil? ((juxt :literal :language :datatype) %))
        (and (string? (:literal %)) (not (and (:language %) (:datatype %)))))]]

   ::api/filters
   [:and
    [:map [:ontology-id {:optional true} ::api/ontology-id]
     [:ontology-ids {:optional true} [:and coll? [:seqable ::api/ontology-id]]]
     [:scope {:optional true} [:maybe ::api/scope]]
     [:broader-uri {:optional true} [:maybe ::api/nonblank-string]]]
    [:fn {:error/message "supply at most one ontology selector"}
     #(not (and (contains? % :ontology-id) (contains? % :ontology-ids)))]]

   ::api/nonnegative-weight
   [:and number? [:fn {:error/message "must be finite and nonnegative"}
                  #(and (Double/isFinite (double %)) (not (neg? %)))]]

   ::api/concept-key
   [:tuple :uuid :string]

   ::api/canonical-map
   [:map-of :string :string]

   ::api/command-result
   [:map [:ontology-id :uuid] [:revision ::api/revision]]})

(defschemas requests
  {::api/ontology-request
   (mu/merge ::api/command-options
             [:map [:name ::api/nonblank-string] [:scope {:optional true} ::api/scope]
              [:description {:optional true} :string] [:base-uri {:optional true} :string]
              [:predicate-policies {:optional true} ::api/policies]])

   ::api/concept-request
   (mu/merge ::api/concept-definition ::api/command-options)

   ::api/rename-request
   (mu/merge ::api/command-options [:map [:new-uri [:and :string [:fn rdf/uri-reference?]]]])

   ::api/merge-request
   (mu/merge ::api/command-options [:map [:target-concept-id :uuid]])

   ::api/update-request
   (mu/merge ::api/command-options [:map [:changes ::api/changes]])

   ::api/relationship-request
   (mu/merge (mu/merge ::api/edge ::api/command-options)
             [:map [:source-ontology-id ::api/ontology-id] [:target-ontology-id ::api/ontology-id]])

   ::api/predicate-request
   (mu/merge ::api/command-options [:map [:predicate ::api/nonblank-string] [:policy ::api/policy]])

   ::api/tbox
   [:map {:closed true}
    [:classes {:optional true} [:vector ::api/schema-record]]
    [:object-properties {:optional true} [:vector ::api/schema-record]]
    [:datatype-properties {:optional true} [:vector ::api/schema-record]]]

   ::api/import-envelope
   [:and
    [:map [:contribution-id :uuid] [:source ::api/source]
     [:expected-revision {:optional true} :int]
     [:resolution-policy {:optional true} ::api/resolution-policy]]
    [:fn {:error/message "imports use contribution-id instead of request-id"}
     #(not (contains? % :request-id))]]

   ::api/contribution-request
   [:and ::api/import-envelope
    [:map [:concepts [:vector ::api/concept-definition]]
     [:relationships {:optional true} [:vector ::api/local-edge]]
     [:tbox {:optional true} ::api/tbox] [:abox {:optional true} [:vector ::api/individual]]
     [:rdf-triples {:optional true} [:vector ::api/rdf-triple]]]]

   ::api/rdf-request
   [:and ::api/import-envelope [:map [:content :string]]]

   ::api/search-request
   [:map [:query-text {:optional true} [:maybe :string]]
    [:lexical-method {:optional true} [:maybe [:enum :overlap :bm25]]]
    [:seed-uris {:optional true} [:maybe [:and coll? [:seqable ::api/nonblank-string]]]]
    [:limit {:optional true} pos-int?] [:max-depth {:optional true} nat-int?]
    [:weights {:optional true} [:map-of :keyword ::api/nonnegative-weight]]
    [:embed-query {:optional true} [:maybe ifn?]]
    [:embedding-model {:optional true} ::api/embedding-model]]})

(defschemas records
  {::api/concept-result
   (mu/merge ::api/command-result [:map [:concept-id :uuid] [:uri ::api/nonblank-string]])

   ::api/relationship-result
   (mu/merge (mu/merge ::api/command-result ::api/edge) [:map [:relationship-id :uuid]])

   ::api/predicate-result
   (mu/merge ::api/command-result [:map [:predicate ::api/nonblank-string] [:policy ::api/policy]])

   ::api/contribution-result
   (mu/merge ::api/command-result
             [:map [:contribution-id :uuid] [:concept-count nat-int?]
              [:relationship-count nat-int?] [:canonical-map ::api/canonical-map]])

   ::api/ontology
   [:map [:ontology-id :uuid] [:name ::api/nonblank-string] [:revision ::api/revision]
    [:predicate-policies ::api/policies] [:created-at :string]
    [:scope {:optional true} ::api/scope] [:description {:optional true} :string]
    [:base-uri {:optional true} :string]]

   ::api/concept
   (mu/merge ::api/concept-definition
             [:map [:concept-id :uuid] [:ontology-id :uuid] [:created-at :string]
              [:updated-at {:optional true} :string]
              [:entity-type {:optional true} [:or :string :keyword]]
              [:broader [:set :string]] [:narrower [:set :string]] [:related [:set :string]]
              [:relations [:map-of :string [:set ::api/concept-key]]]])

   ::api/concept-map
   [:map-of ::api/concept-key ::api/concept]

   ::api/relationship
   (mu/merge ::api/edge [:map [:created-at :string] [:relationship-id {:optional true} :uuid]])

   ::api/source-record
   (mu/merge ::api/source [:map [:registered-at :string]])

   ::api/resolution-match
   [:map [:source1-uri :string] [:source2-uri :string]
    [:match-type :string] [:similarity-score number?]]

   ::api/resolution
   [:map [:exact-matches nat-int?] [:semantic-matches nat-int?]
    [:matches {:optional true} [:vector ::api/resolution-match]]]

   ::api/contribution
   [:map [:contribution-id :uuid] [:ontology-id :uuid] [:source ::api/source]
    [:input [:and ::api/contribution-request [:map [:ontology-id :uuid]]]]
    [:canonical-map ::api/canonical-map] [:resolution ::api/resolution] [:result ::api/contribution-result]
    [:recorded-at :string]]

   ::api/snapshot
   [:map [:ontology-id :uuid] [:revision ::api/revision] [:concepts [:vector ::api/concept]]
    [:relationships [:vector ::api/relationship]] [:tbox ::api/tbox] [:abox [:vector ::api/individual]]
    [:rdf-triples [:vector ::api/rdf-triple]] [:ttl-output :string] [:triple-count nat-int?]
    [:checksum [:re #"[0-9a-f]{64}"]]]

   ::api/graph-event-types
   [:set :qualified-keyword]})

(defschemas search
  {::api/search-results
   [:vector (mu/merge ::api/concept [:map [:score ::api/nonnegative-weight]])]})

(defn- result [success] [:or success ::api/anomaly])
(defn- signature [arguments success] [:=> (into [:cat] arguments) (result success)])

(defschemas functions
  {::api/create-ontology! (signature [::api/context ::api/ontology-request] ::api/command-result)
   ::api/create-concept! (signature [::api/context ::api/ontology-id ::api/concept-request] ::api/concept-result)
   ::api/delete-concept! (signature [::api/context ::api/ontology-id :uuid ::api/command-options] ::api/concept-result)
   ::api/rename-concept! (signature [::api/context ::api/ontology-id :uuid ::api/rename-request] ::api/concept-result)
   ::api/merge-concepts! (signature [::api/context ::api/ontology-id :uuid ::api/merge-request] ::api/concept-result)
   ::api/update-concept! (signature [::api/context ::api/ontology-id :uuid ::api/update-request] ::api/concept-result)
   ::api/create-relationship! (signature [::api/context ::api/relationship-request] ::api/relationship-result)
   ::api/register-predicate! (signature [::api/context ::api/ontology-id ::api/predicate-request] ::api/predicate-result)
   ::api/import-contribution! (signature [::api/context ::api/ontology-id ::api/contribution-request] ::api/contribution-result)
   ::api/import-rdf! (signature [::api/context ::api/ontology-id ::api/rdf-request] ::api/contribution-result)
   ::api/get-ontology (signature [::api/context ::api/ontology-id] [:maybe ::api/ontology])
   ::api/list-ontologies (signature [::api/context] [:vector ::api/ontology])
   ::api/ontology-exists? (signature [::api/context ::api/ontology-id] :boolean)
   ::api/get-revision (signature [::api/context ::api/ontology-id] [:maybe ::api/revision])
   ::api/get-concepts [:function (signature [::api/context] [:vector ::api/concept])
                  (signature [::api/context ::api/filters] [:vector ::api/concept])]
   ::api/get-concept (signature [::api/context ::api/ontology-id :uuid] [:maybe ::api/concept])
   ::api/get-concept-by-uri (signature [::api/context ::api/ontology-id ::api/nonblank-string] [:maybe ::api/concept])
   ::api/get-concept-map [:function (signature [::api/context] ::api/concept-map)
                     (signature [::api/context ::api/filters] ::api/concept-map)]
   ::api/get-relationships (signature [::api/context ::api/ontology-id] [:vector ::api/relationship])
   ::api/get-contributions (signature [::api/context ::api/ontology-id] [:vector ::api/contribution])
   ::api/get-sources (signature [::api/context ::api/ontology-id] [:vector ::api/source-record])
   ::api/get-canonical-mappings (signature [::api/context ::api/ontology-id] ::api/canonical-map)
   ::api/export-snapshot [:function (signature [::api/context ::api/ontology-id] ::api/snapshot)
                     (signature [::api/context ::api/ontology-id [:maybe ::api/revision]] ::api/snapshot)]
   ::api/search (signature [::api/context ::api/ontology-id ::api/search-request] ::api/search-results)})
