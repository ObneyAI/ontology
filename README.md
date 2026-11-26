# Ontology

A Clojure library for tenant-scoped concept graphs, built on Grain's event store.
It provides typed relationships, hierarchy validation, contribution imports,
entity resolution, search, and revision snapshots.

The public API is `ai.obney.ontology.interface`. Each function documents its
arguments, results, and errors in the [API namespace](src/ai/obney/ontology/interface.clj)
and exposes a Malli schema.

Applications supply an event store, a KV store for read-model caching, and
authenticated tenant context. Extraction and embedding providers are managed
by the application.

## Installation

Add a local checkout to your application's `deps.edn`:

```clojure
{:deps {ai.obney/ontology {:local/root "../ontology-exp"}}}
```

Library dependencies and pinned versions are listed in [deps.edn](deps.edn).

## Quick start

Start a REPL with the JVM options required by the LMDB cache:

```sh
clojure -J--add-opens=java.base/java.nio=ALL-UNNAMED \
        -J--add-exports=java.base/sun.nio.ch=ALL-UNNAMED
```

```clojure
(require '[ai.obney.grain.event-store-v3.interface :as es]
         '[ai.obney.grain.kv-store.interface :as kv]
         '[ai.obney.grain.kv-store-lmdb.interface :as lmdb]
         '[ai.obney.ontology.interface :as ontology])

(def event-store
  (es/start {:conn {:type :in-memory}}))

(def cache
  (kv/start
    (lmdb/->KV-Store-LMDB
      {:storage-dir "target/ontology-cache"
       :db-name     "read-models"
       :map-size    (* 64 1024 1024)})))

(def ctx
  {:event-store event-store
   :cache       cache
   :tenant-id   (random-uuid)})

(def created
  (ontology/create-ontology! ctx {:name "Biology" :scope :biology}))
(def ontology-id (:ontology-id created))

(ontology/create-concept! ctx ontology-id
  {:uri "urn:biology:organism"
   :label "Organism"
   :description "An individual living system."
   :scope :biology})

(def plant
  (ontology/create-concept! ctx ontology-id
    {:uri "urn:biology:plant"
     :label "Plant"
     :description "A photosynthetic organism."
     :scope :biology
     :broader ["urn:biology:organism"]}))
(def concept-id (:concept-id plant))

(ontology/get-concept ctx ontology-id concept-id)
(ontology/get-concept-by-uri ctx ontology-id "urn:biology:plant")
(ontology/get-concepts ctx {:ontology-id ontology-id})
(ontology/search ctx ontology-id {:query-text "organism" :limit 10})
```

The examples below use this context and graph. Grain caches projected read models
and their event watermarks in `:cache`, allowing reads to resume from cached
state and process subsequent events. It also maintains an in-process L1 cache.

The application owns both stores. Close them after completing the examples or
during application shutdown:

```clojure
(kv/stop cache)
(es/stop event-store)
```

For a persistent event store, supply a stable `:ontology/store-id` in the context
to reuse cached projections across store restarts. This ID must uniquely identify
the underlying event store and change when that store is replaced. The in-memory
example uses automatic store-instance isolation.

Mutations return result maps containing the committed revision and operation
identities. Expected failures return Cognitect anomaly maps. Check
`:cognitect.anomalies/category` before consuming a result, including results
from `ontology-exists?`.

## Concepts and relationships

Concept creation requires nonblank `:uri`, `:label`, and `:description` strings.
Each concept has a UUID `:concept-id` and an ontology-qualified URI. Individual
lookups accept either identity and return the same record, including derived
relationship views.

```clojure
(ontology/update-concept! ctx ontology-id concept-id
  {:changes {:label "Plants"
             :alt-labels ["Plantae"]}})

(ontology/register-predicate! ctx ontology-id
  {:predicate "app:related"
   :policy {:symmetric? true}})

(ontology/create-relationship! ctx
  {:source-ontology-id ontology-id
   :source-uri "urn:biology:plant"
   :target-ontology-id ontology-id
   :target-uri "urn:biology:organism"
   :predicate "app:related"})
```

Updates support `:label`, `:description`, `:scope`, `:broader`, `:indicators`,
`:attributes`, `:alt-labels`, `:provenance`, `:entity-type`, `:embedding`, and
`:embedding-model`. Supplied values replace entire fields, including maps;
omitted fields remain unchanged. Concept ID, ontology ID, and URI are immutable.
An empty `:broader` vector removes all parents. Hierarchy changes maintain
inverse views and reject cycles.

Predicate policies support `:symmetric?`, `:inverse`, `:hierarchical?`, and
`:cross-ontology?`. Default policies cover `skos:broader`, `skos:narrower`,
`skos:related`, `owl:sameAs`, and `rdfs:subClassOf`. Ontology creation may supply
policy overrides. `register-predicate!` adds a policy for a new predicate.

Both relationship endpoints must exist in the tenant. Cross-ontology links
require permission in the source graph's predicate policy. Relationship reads
and snapshots include permitted incident links and their logical inverse views.

## Collection reads

`get-concepts` and `get-concept-map` accept a context and optional filters:

| Filter | Selection |
| --- | --- |
| `:ontology-id` | One graph |
| `:ontology-ids` | A collection of graphs |
| `:scope` | Concepts with the specified scope |
| `:broader-uri` | Concepts with the specified direct parent |

Use at most one ontology selector. Without a selector, reads span the tenant.
An empty `:ontology-ids` collection selects nothing. Any missing explicitly
selected graph returns a `not-found` anomaly.

```clojure
(ontology/get-concepts ctx {:ontology-id ontology-id :scope :biology})
(ontology/get-concept-map ctx {:ontology-ids [ontology-id]})
(ontology/list-ontologies ctx)
```

`get-concepts` returns a vector sorted by `[ontology-id uri]`.
`get-concept-map` uses those qualified identities as keys, preserving equal
URIs across graphs.

## Contributions and RDF

A contribution atomically imports concepts, relationships, schema, instances,
and RDF triples into one graph.

```clojure
(def request
  {:contribution-id (random-uuid)
   :source {:source-id (random-uuid)
            :content-hash "sha256-of-the-source-bytes"
            :producer {:name "catalog-parser" :version "1"}}
   :concepts [{:uri "urn:biology:oak"
               :label "Oak"
               :description "A tree in the genus Quercus."
               :scope :biology
               :broader ["urn:biology:plant"]}]})

(ontology/import-contribution! ctx ontology-id request)
```

Optional fields include `:relationships`, `:tbox`, `:abox`, `:rdf-triples`,
`:resolution-policy`, and `:expected-revision`. Imports upsert supplied records;
omission does not delete existing knowledge. Supplied parent lists replace
prior parents. Contribution relationships must remain within the target graph.

Set `:resolution-policy` to `{:match-labels? true :similarity-threshold 0.85}`
to enable normalized-label and compatible embedding matching. Existing concept
identities take precedence when selecting canonical URIs. Producer metadata
and evidence references are retained as opaque data.

`import-rdf!` accepts the same source and contribution identity fields, with
`:content` containing the supported N-Triples subset. It supports URI subjects
and URI or literal objects, including language and datatype literals. Blank
nodes and malformed statements are rejected. Custom object predicates require
registered policies.

## Retries and concurrency

Manual mutations accept an optional UUID `:request-id`. Imports use the required
`:contribution-id` as their retry key and reject `:request-id`.

The same key and payload return the original committed result, even after later
writes. Reusing a key with a changed payload returns a conflict. Ontology creation
keys are scoped to the tenant; other mutation keys are scoped to the graph.
Entity UUIDs are independent of retry keys. A new contribution ID may import
another interpretation of the same source bytes.

Every mutation checks concurrency atomically at commit. An optional
`:expected-revision` adds a caller-supplied precondition:

```clojure
(ontology/update-concept! ctx ontology-id concept-id
  {:changes {:description "A member of the kingdom Plantae."}
   :request-id (random-uuid)
   :expected-revision (ontology/get-revision ctx ontology-id)})
```

Graph revisions start at 1. Omitting `:expected-revision` validates the mutation
against the graph state read during execution.

## Search

Search combines lexical matching, graph traversal, and optional embedding
comparisons within one ontology:

```clojure
(ontology/search ctx ontology-id
  {:query-text "tree"
   :seed-uris ["urn:biology:plant"]
   :max-depth 2
   :limit 10
   :weights {:lexical 1.0 :graph 1.0 :embedding 1.0}})
```

The default limit is 10 and traversal depth is 2. Traversal includes seeds and
reachable broader, narrower, and related concepts through the requested depth.
The result limit applies after combining signal ranks. Results contain concept
records with `:score`; scores represent ranking strength, not probabilities.
URI order breaks ties.

For corpus-weighted text search, pass `:lexical-method :bm25` to `ontology/search`. Term statistics use concepts in the requested ontology. Omitting the option uses the existing token-overlap ranking.

For embedding search, supply `:embed-query`, a function from query text to a
vector, and `:embedding-model`. Comparisons require matching model identifiers
and valid vectors of equal dimension. Unusable vectors contribute no signal.

## History and snapshots

| Operation | Result |
| --- | --- |
| `get-revision` | Graph revision |
| `get-relationships` | Logical relationships incident to the graph |
| `get-sources` | Source versions and registration times |
| `get-contributions` | Contribution history and resolution evidence |
| `get-canonical-mappings` | Accumulated source URI → canonical URI mappings |
| `export-snapshot` | Graph data, Turtle output, statement count, and SHA-256 checksum |

These operations take `ctx` and `ontology-id`. `export-snapshot` also accepts
an optional positive revision; omission or `nil` selects the latest revision.

Contribution records contain `:contribution-id`, `:ontology-id`, `:source`,
`:input`, `:canonical-map`, `:resolution`, `:result`, and `:recorded-at`.
The input preserves the graph-qualified contribution before canonicalization.
For RDF imports, it contains the parsed contribution rather than source text.

## Error handling

Expected failures return maps with `:cognitect.anomalies/category` and a message.

| Situation | Result |
| --- | --- |
| Missing ontology, concept, or revision lookup | `nil` |
| Ontology existence check | Boolean, or an anomaly for invalid input/context |
| Existing graph with no matching records | Empty collection |
| Invalid arguments or malformed RDF | `:cognitect.anomalies/incorrect` |
| Missing tenant context | `:cognitect.anomalies/forbidden` |
| Missing selected graph, mutation target, or export revision | `:cognitect.anomalies/not-found` |
| Stale revision, changed retry payload, or concurrent write | `:cognitect.anomalies/conflict` |

Multi-graph reads return an anomaly if any selected graph is missing. Storage
anomalies retain their categories. Unexpected exceptions, including embedding
provider exceptions, propagate.

## Malli contracts

Public Vars expose argument and result schemas through `:malli/schema` metadata.
Schemas cover all arities, anomaly results, and nullable lookups.

```clojure
(require '[malli.core :as m]
         '[malli.instrument :as mi])

(def search-schema (m/schema (:malli/schema (meta #'ontology/search))))
(m/validate (first (m/children search-schema))
            [ctx ontology-id {:query-text "plant"}])

(mi/collect! {:ns 'ai.obney.ontology.interface})
(def instrumentation
  {:data (select-keys (m/function-schemas) ['ai.obney.ontology.interface])})
(mi/instrument! instrumentation)

;; Disable instrumentation after development-time checks.
(mi/unstrument! instrumentation)
```

Instrumentation is opt-in. It throws on invalid arguments before normal anomaly
handling runs. Resource existence, hierarchy, revision, and policy checks run
when operations execute. Request maps generally permit additional fields;
predicate policies and TBox sections reject unknown keys.

## Development

Run the test suite or a focused namespace with the Clojure CLI:

```sh
clojure -M:test
clojure -M:test ai.obney.ontology.rdf-test
```

The suite covers public contracts, graph algorithms, [property tests](docs/PROPERTY-TESTS.md), and SQLite
persistence, replay, and concurrent writes. Additional verification requires
Python 3 and the Allium CLI:

```sh
bash scripts/verify-tests.sh
```

This runs specification checks, obligation mapping, tests, an extended property
campaign, and a mutation audit. Reports are written to `evidence/`.

The [Allium specification](specs/ontology.allium) defines behavioral contracts.
The [coverage ledger](docs/TEST-COVERAGE.md) maps obligations to executable tests.

Track ontology health through the core suite and raw retrieval coverage. The retrieval audit measures whether search returns the annotated source turns before any reranking or answer generation. [Primary ontology checks and current results](docs/BENCHMARKS.md#primary-ontology-checks)

## Performance and architecture

Run benchmarks against in-memory and SQLite stores:

```sh
python3 scripts/benchmark.py --output target/benchmarks/my-baseline
```

See [benchmark methodology](docs/BENCHMARKS.md) for workloads and measurement
controls, and [contribution profiling](docs/PROFILING.md) for latency and
allocation analysis.

Implementation documentation covers [scoped reads](docs/SCOPED-READS.md),
[contribution deltas](docs/CONTRIBUTION-DELTAS.md), and
[incremental graph indexes](docs/MUTATION-INDEXES.md).
