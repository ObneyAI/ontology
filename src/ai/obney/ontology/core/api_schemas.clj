(ns ai.obney.ontology.core.api-schemas
  "Malli data and function contracts for the public interface.
   These describe valid calls; instrumentation is opt-in. Graph-dependent
   validation (existence, policy, hierarchy, retries) remains in operations."
  (:require [ai.obney.ontology.core.schemas :as stored]
            [ai.obney.grain.schema-util.interface :refer [defschemas]]
            [ai.obney.grain.event-store-v3.interface.protocol :as event-store]
            [clojure.string :as str]
            [malli.util :as mu]))

(def ^:private mutable-fields
  #{:label :description :broader :indicators :attributes :embedding :embedding-model
    :scope :alt-labels :provenance :entity-type})

(defschemas common
  {::nonblank-string
   [:and :string [:fn {:error/message "must not be blank"} #(not (str/blank? %))]]

   ::ontology-id
   [:or :uuid ::nonblank-string]

   ::context
   [:map
    [:event-store [:fn {:error/message "must implement Grain EventStore"}
                   #(satisfies? event-store/EventStore %)]]
    [:tenant-id :uuid]]

   ::anomaly
   [:map
    [:cognitect.anomalies/category :qualified-keyword]
    [:cognitect.anomalies/message {:optional true} :string]]

   ::revision
   [:int {:min 1}]

   ::scope
   stored/scope

   ::policy
   stored/policy

   ::policies
   stored/policies

   ::command-options
   [:map [:request-id {:optional true} :uuid] [:expected-revision {:optional true} :int]]})

(defschemas fields
  {::concept-definition
   (mu/merge stored/concept
             [:map [:uri ::nonblank-string] [:label ::nonblank-string] [:description ::nonblank-string]
              ;; Opaque stored vectors/models are intentional: search skips unusable
              ;; vectors instead of rejecting them at creation or import time.
              [:embedding {:optional true} :any] [:embedding-model {:optional true} :any]])

   ::changes
   [:and
    (mu/merge stored/changes
              [:map [:label {:optional true} ::nonblank-string]
               [:description {:optional true} ::nonblank-string]
               [:embedding {:optional true} :any] [:embedding-model {:optional true} :any]])
    [:fn {:error/message "must supply at least one mutable field"}
     #(some (partial contains? %) mutable-fields)]]

   ::edge
   [:map [:source-ontology-id :uuid] [:source-uri ::nonblank-string]
    [:target-ontology-id :uuid] [:target-uri ::nonblank-string] [:predicate ::nonblank-string]
    [:metadata {:optional true} :any]]

   ::source
   [:map [:source-id :uuid] [:content-hash ::nonblank-string] [:producer {:optional true} :map]]

   ::resolution-policy
   [:map [:match-labels? {:optional true} :boolean]
    [:similarity-threshold {:optional true}
     [:and number? [:fn {:error/message "must be finite and between 0 and 1"}
                    #(and (Double/isFinite (double %)) (<= 0 % 1))]]]]

   ::local-edge
   [:and
    [:map [:predicate ::nonblank-string]
     [:subject {:optional true} ::nonblank-string] [:object {:optional true} ::nonblank-string]
     [:source-uri {:optional true} ::nonblank-string] [:target-uri {:optional true} ::nonblank-string]
     [:source-ontology-id {:optional true} :uuid] [:target-ontology-id {:optional true} :uuid]
     [:metadata {:optional true} :any]]
    [:fn {:error/message "must supply source and target URIs"}
     #(and (or (:subject %) (:source-uri %)) (or (:object %) (:target-uri %)))]]

   ::schema-record
   [:map [:uri ::nonblank-string] [:label {:optional true} :string]
    [:domain {:optional true} ::nonblank-string] [:range {:optional true} ::nonblank-string]
    [:datatype {:optional true} ::nonblank-string]]

   ::individual
   [:map [:uri ::nonblank-string] [:type {:optional true} ::nonblank-string]
    [:properties {:optional true} :map]]

   ::rdf-triple
   [:and
    [:map [:subject ::nonblank-string] [:predicate ::nonblank-string]
     [:object-uri {:optional true} [:maybe ::nonblank-string]]
     [:literal {:optional true} [:maybe :string]]
     [:language {:optional true} [:maybe :string]]
     [:datatype {:optional true} [:maybe ::nonblank-string]]]
    [:fn {:error/message "must have a URI object or literal with optional language or datatype"}
     #(if (some? (:object-uri %))
        (every? nil? ((juxt :literal :language :datatype) %))
        (and (string? (:literal %)) (not (and (:language %) (:datatype %)))))]]

   ::filters
   [:and
    [:map [:ontology-id {:optional true} ::ontology-id]
     [:ontology-ids {:optional true} [:and coll? [:seqable ::ontology-id]]]
     [:scope {:optional true} [:maybe ::scope]]
     [:broader-uri {:optional true} [:maybe ::nonblank-string]]]
    [:fn {:error/message "supply at most one ontology selector"}
     #(not (and (contains? % :ontology-id) (contains? % :ontology-ids)))]]

   ::nonnegative-weight
   [:and number? [:fn {:error/message "must be finite and nonnegative"}
                  #(and (Double/isFinite (double %)) (not (neg? %)))]]

   ::concept-key
   [:tuple :uuid :string]

   ::canonical-map
   [:map-of :string :string]

   ::command-result
   [:map [:ontology-id :uuid] [:revision ::revision]]})

(defschemas requests
  {::ontology-request
   (mu/merge ::command-options
             [:map [:name ::nonblank-string] [:scope {:optional true} ::scope]
              [:description {:optional true} :string] [:base-uri {:optional true} :string]
              [:predicate-policies {:optional true} ::policies]])

   ::concept-request
   (mu/merge ::concept-definition ::command-options)

   ::update-request
   (mu/merge ::command-options [:map [:changes ::changes]])

   ::relationship-request
   (mu/merge (mu/merge ::edge ::command-options)
             [:map [:source-ontology-id ::ontology-id] [:target-ontology-id ::ontology-id]])

   ::predicate-request
   (mu/merge ::command-options [:map [:predicate ::nonblank-string] [:policy ::policy]])

   ::tbox
   [:map {:closed true}
    [:classes {:optional true} [:vector ::schema-record]]
    [:object-properties {:optional true} [:vector ::schema-record]]
    [:datatype-properties {:optional true} [:vector ::schema-record]]]

   ::import-envelope
   [:and
    [:map [:contribution-id :uuid] [:source ::source]
     [:expected-revision {:optional true} :int]
     [:resolution-policy {:optional true} ::resolution-policy]]
    [:fn {:error/message "imports use contribution-id instead of request-id"}
     #(not (contains? % :request-id))]]

   ::contribution-request
   [:and ::import-envelope
    [:map [:concepts [:vector ::concept-definition]]
     [:relationships {:optional true} [:vector ::local-edge]]
     [:tbox {:optional true} ::tbox] [:abox {:optional true} [:vector ::individual]]
     [:rdf-triples {:optional true} [:vector ::rdf-triple]]]]

   ::rdf-request
   [:and ::import-envelope [:map [:content :string]]]

   ::search-request
   [:map [:query-text {:optional true} [:maybe :string]]
    [:lexical-method {:optional true} [:maybe [:enum :overlap :bm25]]]
    [:seed-uris {:optional true} [:maybe [:and coll? [:seqable ::nonblank-string]]]]
    [:limit {:optional true} pos-int?] [:max-depth {:optional true} nat-int?]
    [:weights {:optional true} [:map-of :keyword ::nonnegative-weight]]
    [:embed-query {:optional true} [:maybe ifn?]]
    [:embedding-model {:optional true} :any]]})

(defschemas records
  {::concept-result
   (mu/merge ::command-result [:map [:concept-id :uuid] [:uri ::nonblank-string]])

   ::relationship-result
   (mu/merge (mu/merge ::command-result ::edge) [:map [:relationship-id :uuid]])

   ::predicate-result
   (mu/merge ::command-result [:map [:predicate ::nonblank-string] [:policy ::policy]])

   ::contribution-result
   (mu/merge ::command-result
             [:map [:contribution-id :uuid] [:concept-count nat-int?]
              [:relationship-count nat-int?] [:canonical-map ::canonical-map]])

   ::ontology
   [:map [:ontology-id :uuid] [:name ::nonblank-string] [:revision ::revision]
    [:predicate-policies ::policies] [:created-at :string]
    [:scope {:optional true} ::scope] [:description {:optional true} :string]
    [:base-uri {:optional true} :string]]

   ::concept
   (mu/merge ::concept-definition
             [:map [:concept-id :uuid] [:ontology-id :uuid] [:created-at :string]
              [:updated-at {:optional true} :string]
              ;; Imported entity type may come from a keyword-valued scope.
              [:entity-type {:optional true} [:or :string :keyword]]
              [:broader [:set :string]] [:narrower [:set :string]] [:related [:set :string]]
              [:relations [:map-of :string [:set ::concept-key]]]])

   ::concept-map
   [:map-of ::concept-key ::concept]

   ::relationship
   (mu/merge ::edge [:map [:created-at :string] [:relationship-id {:optional true} :uuid]])

   ::source-record
   (mu/merge ::source [:map [:registered-at :string]])

   ::resolution-match
   [:map [:source1-uri :string] [:source2-uri :string]
    [:match-type :string] [:similarity-score number?]]

   ::resolution
   [:map [:exact-matches nat-int?] [:semantic-matches nat-int?]
    [:matches {:optional true} [:vector ::resolution-match]]]

   ::contribution
   [:map [:contribution-id :uuid] [:ontology-id :uuid] [:source ::source]
    [:input [:and ::contribution-request [:map [:ontology-id :uuid]]]]
    [:canonical-map ::canonical-map] [:resolution ::resolution] [:result ::contribution-result]
    [:recorded-at :string]]

   ::snapshot
   [:map [:ontology-id :uuid] [:revision ::revision] [:concepts [:vector ::concept]]
    [:relationships [:vector ::relationship]] [:tbox ::tbox] [:abox [:vector ::individual]]
    [:rdf-triples [:vector ::rdf-triple]] [:ttl-output :string] [:triple-count nat-int?]
    [:checksum [:re #"[0-9a-f]{64}"]]]

   ::graph-event-types
   [:set :qualified-keyword]})

(defschemas search
  {::search-results
   [:vector (mu/merge ::concept [:map [:score ::nonnegative-weight]])]})

(defn- result [success] [:or success ::anomaly])
(defn- signature [arguments success] [:=> (into [:cat] arguments) (result success)])

(defschemas functions
  {::create-ontology! (signature [::context ::ontology-request] ::command-result)
   ::create-concept! (signature [::context ::ontology-id ::concept-request] ::concept-result)
   ::update-concept! (signature [::context ::ontology-id :uuid ::update-request] ::concept-result)
   ::create-relationship! (signature [::context ::relationship-request] ::relationship-result)
   ::register-predicate! (signature [::context ::ontology-id ::predicate-request] ::predicate-result)
   ::import-contribution! (signature [::context ::ontology-id ::contribution-request] ::contribution-result)
   ::import-rdf! (signature [::context ::ontology-id ::rdf-request] ::contribution-result)
   ::get-ontology (signature [::context ::ontology-id] [:maybe ::ontology])
   ::list-ontologies (signature [::context] [:vector ::ontology])
   ::ontology-exists? (signature [::context ::ontology-id] :boolean)
   ::get-revision (signature [::context ::ontology-id] [:maybe ::revision])
   ::get-concepts [:function (signature [::context] [:vector ::concept])
                  (signature [::context ::filters] [:vector ::concept])]
   ::get-concept (signature [::context ::ontology-id :uuid] [:maybe ::concept])
   ::get-concept-by-uri (signature [::context ::ontology-id ::nonblank-string] [:maybe ::concept])
   ::get-concept-map [:function (signature [::context] ::concept-map)
                     (signature [::context ::filters] ::concept-map)]
   ::get-relationships (signature [::context ::ontology-id] [:vector ::relationship])
   ::get-contributions (signature [::context ::ontology-id] [:vector ::contribution])
   ::get-sources (signature [::context ::ontology-id] [:vector ::source-record])
   ::get-canonical-mappings (signature [::context ::ontology-id] ::canonical-map)
   ::export-snapshot [:function (signature [::context ::ontology-id] ::snapshot)
                     (signature [::context ::ontology-id [:maybe ::revision]] ::snapshot)]
   ::search (signature [::context ::ontology-id ::search-request] ::search-results)})
