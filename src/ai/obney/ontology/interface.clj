(ns ai.obney.ontology.interface
  "Public ontology graph API.

  Context
    Every function takes {:event-store store :tenant-id tenant-id}.
    The host owns store lifecycle and authentication.

  Identities
    Ontology IDs accept UUIDs or nonblank strings. UUID strings are parsed;
    other strings normalize deterministically to UUIDs.
    Concept IDs are UUIDs. URIs are domain identifiers.

  Command options
    :request-id         Optional UUID for manual mutation retries.
    :expected-revision  Optional integer precondition on the graph revision.
    :contribution-id    Required import retry key, instead of :request-id.

    The same retry key and payload recover the original result. Changed
    payloads conflict. Ontology creation keys are tenant-scoped; other manual
    keys are graph-scoped. Entity UUIDs are independent of retry keys.
    Every commit checks concurrency.

  Errors
    Expected failures return maps with :cognitect.anomalies/category and
    :cognitect.anomalies/message. Categories use that same namespace:

      incorrect  Invalid input.
      forbidden  Missing tenant context.
      not-found  Missing required resources.
      conflict   Stale revisions, changed retry payloads or competing writes.

    Storage anomalies retain their categories. Unexpected exceptions propagate.
    Missing point lookups return nil. Explicit collection scopes must exist.
    Check for an anomaly before consuming any result, including booleans."
  (:require [malli.core :as m]
            [ai.obney.ontology.core :as core]
            [ai.obney.ontology.core.api-schemas :as schemas]))

(defn create-ontology!
  "Create a tenant-owned ontology.

  Request
    Required: :name (nonblank string).
    Optional: :scope (keyword/string), :description, :base-uri and
              :predicate-policies (predicate strings mapped to policies).
    Commands: :request-id, :expected-revision.

    See register-predicate! for policy fields. A new graph starts at revision 1;
    its pre-creation revision is 0.

  Returns
    {:ontology-id UUID :revision integer}, or an anomaly.
    A retry returns the original generated ontology ID."
  {:malli/schema (m/deref ::schemas/create-ontology!)}
  [ctx params]
  (core/create-ontology! ctx params))

(defn create-concept!
  "Create a concept in ontology-id.

  Request
    Required: :uri, :label and :description (nonblank strings).
    Optional:
      :scope
      :broader          Vector of parent URIs.
      :indicators       Vector of strings.
      :attributes, :provenance, :entity-type, :alt-labels
      :embedding, :embedding-model
    Commands: :request-id, :expected-revision.

    Parents must exist in this graph. The hierarchy must be valid.

  Returns
    {:ontology-id UUID :concept-id UUID :uri string :revision integer}
    or an anomaly.

  Errors
    Duplicate URI: conflict. Missing ontology: not-found."
  {:malli/schema (m/deref ::schemas/create-concept!)}
  [ctx ontology-id concept]
  (core/create-concept! ctx ontology-id concept))

(defn update-concept!
  "Update a concept UUID within ontology-id.

  Request
    {:changes {...}}, with at least one mutable field:
      :label, :description
      :broader, :indicators, :attributes
      :scope, :alt-labels, :provenance, :entity-type
      :embedding, :embedding-model

    Optional command fields: :request-id, :expected-revision.
    Positional identities take precedence over request-map identity fields.

  Update behavior
    Supplied values replace their fields; maps are not deep-merged.
    Omitted fields remain unchanged. Concept ID, ontology ID and URI are immutable.
    :broader [] removes all parents and maintains inverse views.

  Returns
    {:ontology-id UUID :concept-id UUID :uri string :revision integer}
    or an anomaly. A missing ontology or concept returns not-found."
  {:malli/schema (m/deref ::schemas/update-concept!)}
  [ctx ontology-id concept-id request]
  (core/update-concept! ctx ontology-id concept-id request))

(defn create-relationship!
  "Create a link between qualified endpoints.

  Edge request
    Required:
      :source-ontology-id, :source-uri
      :target-ontology-id, :target-uri
      :predicate
    Optional: :metadata.
    Commands: :request-id, :expected-revision (source-ontology scoped).

    Both endpoints must exist within the tenant. Predicate policy must permit
    the link, including any cross-ontology access. Self-links are invalid.

  Returns
    Endpoint/predicate fields and optional metadata, plus:
      :relationship-id
      :ontology-id      Source graph.
      :revision         Source graph's new revision.
    Expected failures return an anomaly.

  Errors
    Duplicate link: conflict. Missing graph or endpoint: not-found."
  {:malli/schema (m/deref ::schemas/create-relationship!)}
  [ctx edge]
  (core/create-relationship! ctx edge))

(defn register-predicate!
  "Register a predicate policy within ontology-id.

  Request
    {:predicate string :policy map}
    Optional command fields: :request-id, :expected-revision.

  Policy fields
    :symmetric?       Boolean.
    :cross-ontology?  Boolean.
    :hierarchical?    Boolean.
    :inverse          Predicate string.

    Unsupported policy keys are rejected. This operation cannot replace
    existing policies.

  Returns
    {:ontology-id UUID :predicate string :policy map :revision integer}
    or an anomaly.

  Errors
    Duplicate predicate: conflict. Missing ontology: not-found."
  {:malli/schema (m/deref ::schemas/register-predicate!)}
  [ctx ontology-id request]
  (core/register-predicate! ctx ontology-id request))

(defn import-contribution!
  "Atomically import structured knowledge into ontology-id.

  Required request fields
    :contribution-id  UUID; graph-scoped retry key.
    :source           {:source-id UUID :content-hash nonblank-string}
                      May include an opaque :producer map.
    :concepts         Vector of create-concept! definitions.

  Optional request fields
    :relationships, :tbox, :abox, :rdf-triples
    :resolution-policy, :expected-revision

  Relationships and merging
    Relationships use :subject/:object or :source-uri/:target-uri, plus
    :predicate. Endpoints must belong to this graph.
    Omitted records are not deleted. Supplied parent lists replace prior parents.
    Existing concept identities are preserved.

  Resolution
    :resolution-policy {:match-labels? true :similarity-threshold 0.85}
    enables label and compatible embedding matching.
    The threshold must be between 0 and 1.

  Revisions and retries
    Omit :expected-revision to validate against current state.
    An identical retry returns its original result even after later writes.
    Changed payloads conflict. :request-id is rejected; use :contribution-id.

  Returns
    :ontology-id, :revision, :contribution-id
    :concept-count, :relationship-count  Counts in the resulting graph.
    :canonical-map                       Incoming URI -> canonical URI.
    Expected failures return an anomaly."
  {:malli/schema (m/deref ::schemas/import-contribution!)}
  [ctx ontology-id request]
  (core/import-contribution! ctx ontology-id request))

(defn import-rdf!
  "Import the supported N-Triples subset into ontology-id without a model.

  Request
    Required: :content (string), :contribution-id (UUID), and the same :source
              map as import-contribution!.
    Optional: :expected-revision, :resolution-policy.
    :request-id is rejected; use :contribution-id.

  Supported content
    URI subjects and URI or literal objects, including language/datatype
    literals. Blank nodes and malformed statements are rejected.
    Custom object predicates require registered policies.

    Concepts, relationships, schema, instances and raw triples are derived
    from content.

  Returns
    The same result shape and retry behavior as import-contribution!.

  Errors
    Parser failures return incorrect. Storage anomalies retain their categories.
    Unexpected errors propagate."
  {:malli/schema (m/deref ::schemas/import-rdf!)}
  [ctx ontology-id request]
  (core/import-rdf! ctx ontology-id request))

(def graph-event-types
  "Set of ontology event-type keywords for event-store filtering and replay."
  core/graph-event-types)

(defn get-ontology
  "Return ontology metadata, predicate policies and current :revision.

  Returns nil if the ontology is absent in this tenant.
  Invalid input or context returns an anomaly."
  {:malli/schema (m/deref ::schemas/get-ontology)}
  [ctx ontology-id]
  (core/get-ontology ctx ontology-id))

(defn list-ontologies
  "Return this tenant's ontology metadata, policies and revisions.

  Returns a vector sorted by ontology ID, or [] for an empty tenant.
  Invalid context returns an anomaly."
  {:malli/schema (m/deref ::schemas/list-ontologies)}
  [ctx]
  (core/list-ontologies ctx))

(defn ontology-exists?
  "Return true or false for ontology presence in this tenant.

  Invalid input or context returns an anomaly map, not a boolean.
  Check for an anomaly before using the result."
  {:malli/schema (m/deref ::schemas/ontology-exists?)}
  [ctx ontology-id]
  (core/ontology-exists? ctx ontology-id))

(defn get-revision
  "Return the ontology's current integer revision, starting at 1.

  Returns nil if the ontology is absent in this tenant.
  Invalid input or context returns an anomaly."
  {:malli/schema (m/deref ::schemas/get-revision)}
  [ctx ontology-id]
  (core/get-revision ctx ontology-id))

(defn get-concepts
  "Return concept records sorted by qualified [ontology-id uri].

  Filters
    :ontology-id   Select one graph.
    :ontology-ids  Select a collection of graphs.
    :scope         Optional keyword/string filter.
    :broader-uri   Optional direct-parent URI filter.

    Without filters, read the whole tenant. Use only one ontology selector.
    An empty :ontology-ids collection selects nothing.

  Returns
    A vector of records, or [] when nothing matches. Each record includes:
      :concept-id, :ontology-id, :uri  Identity fields.
      Stored concept fields.
      :broader, :narrower, :related   Derived sets.
      :relations                     Predicate -> set of [ontology-id uri].

  Errors
    Invalid input or context returns an anomaly.
    Any missing selected graph returns not-found instead of partial results."
  {:malli/schema (m/deref ::schemas/get-concepts)}
  ([ctx] (core/get-concepts ctx))
  ([ctx filters] (core/get-concepts ctx filters)))

(defn get-concept
  "Return one concept by ontology-id and concept UUID.

  The record has the same fields as get-concepts, including relationship views.

  Returns nil when the graph or concept is absent in this tenant.
  Invalid input or context returns an anomaly."
  {:malli/schema (m/deref ::schemas/get-concept)}
  [ctx ontology-id concept-id]
  (core/get-concept ctx ontology-id concept-id))

(defn get-concept-by-uri
  "Return one concept by ontology-id and nonblank URI.

  An ontology ID is required. The record has the same fields as get-concepts,
  including :concept-id and relationship views.

  Returns nil when the graph or concept is absent in this tenant.
  Invalid input or context returns an anomaly."
  {:malli/schema (m/deref ::schemas/get-concept-by-uri)}
  [ctx ontology-id uri]
  (core/get-concept-by-uri ctx ontology-id uri))

(defn get-concept-map
  "Return concepts keyed by [ontology-id uri].

  Qualified keys preserve equal URIs across graphs. Accepts exactly the same
  filters, validation and missing-graph behavior as get-concepts.
  Without filters, read the whole tenant.

  Returns {} when nothing matches or :ontology-ids is empty.
  Invalid input, invalid context or any missing selected graph returns an anomaly."
  {:malli/schema (m/deref ::schemas/get-concept-map)}
  ([ctx] (core/get-concept-map ctx))
  ([ctx filters] (core/get-concept-map ctx filters)))

(defn get-relationships
  "Return logical relationships incident to ontology-id.

  Includes inverse/symmetric views and permitted cross-ontology links.
  Each record contains qualified endpoints, a predicate and stored edge metadata:
    :source-ontology-id, :source-uri
    :target-ontology-id, :target-uri
    :predicate

  Returns a sorted vector, or [] for an existing graph with no links.
  A missing graph returns not-found. Invalid input or context returns an anomaly."
  {:malli/schema (m/deref ::schemas/get-relationships)}
  [ctx ontology-id]
  (core/get-relationships ctx ontology-id))

(defn get-contributions
  "Return recorded contributions for ontology-id.

  Returns a vector sorted by :recorded-at, then contribution ID.
  Records contain:
    :contribution-id, :ontology-id, :source
    :input, :canonical-map, :resolution
    :result, :recorded-at

  :input retains the graph-qualified contribution before canonicalization.
  For structured imports, this is the submitted envelope. For RDF imports,
  it contains the parsed contribution, not the original RDF text.

  No contributions returns []. A missing graph returns not-found.
  Invalid input or context returns an anomaly."
  {:malli/schema (m/deref ::schemas/get-contributions)}
  [ctx ontology-id]
  (core/get-contributions ctx ontology-id))

(defn get-sources
  "Return source-version records for ontology-id.

  Returns a vector sorted by source ID, then content hash.
  Records contain :source-id, :content-hash, optional :producer and :registered-at.
  Source identity is qualified by graph, source ID and content hash.

  No sources returns []. A missing graph returns not-found.
  Invalid input or context returns an anomaly."
  {:malli/schema (m/deref ::schemas/get-sources)}
  [ctx ontology-id]
  (core/get-sources ctx ontology-id))

(defn get-canonical-mappings
  "Return the graph's accumulated map of source URI to canonical URI.

  No mappings returns {}. A missing graph returns not-found.
  Invalid input or context returns an anomaly."
  {:malli/schema (m/deref ::schemas/get-canonical-mappings)}
  [ctx ontology-id]
  (core/get-canonical-mappings ctx ontology-id))

(defn export-snapshot
  "Export the current graph or a specified historical revision.

  Revision
    Omitted/nil means current. Otherwise, supply a positive integer.

  Returns
    :ontology-id, :revision
    :concepts, :relationships
    :tbox, :abox, :rdf-triples
    :ttl-output
    :triple-count
    :checksum  SHA-256 of the Turtle output.

    Concept records use :concept-id. Relationships include logical inverse
    and symmetric views, plus incident cross-graph links.

  Errors
    Missing graph or revision: not-found. Invalid revision value: incorrect.
    Other expected failures return anomalies. Unexpected errors propagate."
  {:malli/schema (m/deref ::schemas/export-snapshot)}
  ([ctx ontology-id] (core/export-snapshot ctx ontology-id))
  ([ctx ontology-id revision] (core/export-snapshot ctx ontology-id revision)))

(defn search
  "Return ranked concept records within ontology-id, with :score.

  Request options
    :query-text       String; lexical matching uses label/description tokens.
    :seed-uris        Collection of URIs for graph traversal.
    :limit            Positive integer; default 10.
    :max-depth        Nonnegative integer; default 2.
    :weights          Map of :lexical/:graph/:embedding to finite nonnegative
                      numbers. Omitted weights default to 1.0.
    :embed-query      Optional function: query text -> vector.
    :embedding-model  Must match stored vector models for embedding comparisons.

  Ranking
    Combines signal ranks; :score is not a similarity probability.
    URI breaks ties. Returns up to :limit records, or [] with no candidates.

  Errors
    Missing graph: not-found. Invalid input or context returns an anomaly.
    Query-provider exceptions propagate."
  {:malli/schema (m/deref ::schemas/search)}
  [ctx ontology-id request]
  (core/search ctx ontology-id request))
