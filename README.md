# Ontology

A Clojure library for tenant-scoped concept graphs, built on Grain's event store.
The supported public namespace is `ai.obney.ontology.interface`.
Namespaces under `ai.obney.ontology.core` are implementation details.
Every public function has argument, result and failure documentation in
[the API namespace](src/ai/obney/ontology/interface.clj), available through
`clojure.repl/doc` at the REPL.
The former `ai.obney.ontology.algorithms` facade has been removed; graph search,
resolution, RDF import and snapshot export are available through the public
operations described below.

The library owns concepts, typed relationships, hierarchy, graph validation,
source contributions, canonical resolution, search and revision snapshots.
It accepts extracted knowledge as data. Agent execution, extraction workflows,
classification, claims, Living Descriptions, consolidation and harvesting belong
to the application using it.

The core dependency set is Clojure and the pinned `grain-core-v2` project in
[deps.edn](deps.edn). It has no dependency on ORC. Consumers provide the event
store and tenant context; no model service or search index is needed for ordinary
creation, import, lookup, lexical search, graph traversal or export.

## Local dependency

For a sibling checkout:

```clojure
{:deps {ai.obney/ontology {:local/root "../ontology-exp"}}}
```

This is a local dependency coordinate, not a claim that the library has been
published to an artifact repository.

## Create and query a graph

```clojure
(require '[ai.obney.grain.event-store-v3.interface :as es]
         '[ai.obney.ontology.interface :as ontology])

(def store (es/start {:conn {:type :in-memory}
                      :event-pubsub nil :logger nil}))
(def ctx {:event-store store :tenant-id (random-uuid)})

(def created (ontology/create-ontology! ctx {:name "Biology" :scope :biology}))
(def ontology-id (:ontology-id created))

(ontology/create-concept! ctx ontology-id
  {:uri "urn:biology:organism" :label "Organism"
   :description "An individual living system." :scope :biology})

(def plant (ontology/create-concept! ctx ontology-id
  {:uri "urn:biology:plant" :label "Plant"
   :description "A photosynthetic organism." :scope :biology
   :broader ["urn:biology:organism"]}))

(ontology/get-concept ctx ontology-id (:concept-id plant))
(ontology/get-concept-by-uri ctx ontology-id "urn:biology:plant")
(ontology/get-concepts ctx {:ontology-id ontology-id})
(ontology/search ctx ontology-id {:query-text "organism" :limit 10})

;; Stop the store when its application lifecycle ends.
(es/stop store)
```

Ontology, concept and relationship UUIDs are generated independently of command
IDs. An accepted command retry returns its original generated identities.

Commands return result maps, including graph revision and generated identities.
Expected failures from all public operations return Cognitect anomaly maps;
check `:cognitect.anomalies/category` before using a result. Concept lookup and search require tenant context and explicit graph
identity; tenant-wide administrative enumeration is available through
`list-ontologies` and unfiltered `get-concepts` and `get-concept-map`. The tenant
context comes from the host's authenticated application boundary; the library
does not provide authentication.

`update-concept!` takes context, ontology ID, concept ID and a request map
containing `:changes`. Mutable fields are `:label`, `:description`, `:broader`,
`:indicators`, `:attributes`, `:embedding`, `:embedding-model`, `:scope`,
`:alt-labels`, `:provenance` and `:entity-type`. Supplied values replace their
fields, including whole maps; omitted fields remain unchanged. Concept ID,
ontology ID and URI are immutable.
The concept UUID is consistently returned as `:concept-id` by creation, lookup,
listing, search and snapshot export. Pass that UUID to `update-concept!`; URI
lookup remains scoped by ontology ID.
Parent updates maintain the graph's hierarchy views.

Manual mutation requests accept an optional UUID `:request-id` for retry safety
and an optional integer `:expected-revision` for a caller revision precondition.
Omit `:request-id` to generate a new command identity on each call. Retry with the
same request ID and payload to recover the original result; reusing the ID with
changed payload returns a conflict. Ontology creation retry keys are tenant-scoped;
other manual mutation keys are ontology-scoped. Creation generates entity UUIDs
independently of request IDs. Grain command names, IDs and timestamps are internal.
Imports use `:contribution-id` instead and reject `:request-id`.

```clojure
(ontology/update-concept! ctx ontology-id concept-id
  {:changes {:label "Plants"}
   :request-id (random-uuid)
   :expected-revision (ontology/get-revision ctx ontology-id)})

(ontology/register-predicate! ctx ontology-id
  {:predicate "app:related" :policy {:symmetric? true}})
```

Relationships use qualified endpoints:

```clojure
(ontology/create-relationship! ctx
  {:source-ontology-id ontology-id :source-uri "urn:biology:plant"
   :target-ontology-id ontology-id :target-uri "urn:biology:organism"
   :predicate "skos:related"})
```

Applications register their predicates with `register-predicate!`. Built-in
policies cover SKOS broader/narrower/related, OWL sameAs and RDFS subClassOf.
Custom policies can declare cross-ontology access, symmetry and hierarchy.
Application vocabulary has no executable meaning inside the library.
Contribution edges are local to their target graph. Use `create-relationship!`
for an explicitly permitted cross-ontology link; an import rejects endpoints
qualified to a different graph.

## Import a contribution

Run extraction in the application, then submit its output:

```clojure
(def request
  {:contribution-id (random-uuid)
   :source {:source-id (random-uuid)
            :content-hash "sha256-of-the-source-bytes"
            :producer {:name "catalog-parser" :version "1"}}
   :concepts [{:uri "urn:biology:oak" :label "Oak"
               :description "A tree in the genus Quercus." :scope :biology
               :broader ["urn:biology:plant"]}]
   :relationships []
   :tbox {:classes [] :object-properties [] :datatype-properties []}
   :abox []})

(ontology/import-contribution! ctx ontology-id request)
```

Import and search use `[ctx ontology-id request]`. The positional ontology ID
selects the graph; leave `:ontology-id` out of the request map. Contribution
history retains the full input envelope, including that ontology ID.

The contribution ID is the idempotency key. Retry the identical request to
recover its original result. Reusing its ID with changed content is a conflict;
a new contribution with a supplied stale expected revision is also a conflict.
A new contribution ID may interpret the same source bytes again.

`:expected-revision` is optional. Supply it when a change should apply only to
a graph version the caller has reviewed. Without it, the import validates
against current state. Every mutation still uses an atomic append condition
to reject conflicting writes during execution. Imports use additive
upserts: omission does not delete existing knowledge. Supplied parent lists
replace that concept's parent list.

`:resolution-policy {:match-labels? true :similarity-threshold 0.85}` enables
normalized-label resolution and optional matching of supplied embeddings.
Existing concept identities are preferred as canonical. Producer metadata and
source references are data; the library does not execute or inspect a producer.

`import-rdf!` takes `ctx`, `ontology-id` and a request map with the same
source, contribution ID and revision envelope,
plus `:content` containing the supported N-Triples subset. URI subjects and URI
or literal objects are supported, including language and datatype literals.
Blank-node statements and malformed input are rejected. Register policies for
nonstandard object predicates before import. No model call is required.

## History and export

`get-sources`, `get-contributions` and `get-canonical-mappings` accept context
and ontology ID. `export-snapshot` accepts those arguments plus an optional
revision. Its result contains concepts, relationships, TBox, ABox, raw RDF
triples, Turtle output, statement count and checksum. The default revision is
current. Export data comes from graph history.

Contribution records contain `:contribution-id`, `:ontology-id`, `:source`,
`:input`, `:canonical-map`, `:resolution`, `:result` and `:recorded-at`.
The input preserves the graph-qualified contribution before canonicalization.
For RDF imports, it contains the parsed contribution, not the original RDF text.

Search combines lexical matches, optional graph seeds and optional embeddings.
Pass `:seed-uris` for graph traversal. An optional `:embed-query` function and
`:embedding-model` identify the query embedding provider; it is supplied by the
host, not loaded from an orchestration component.

## Concept collection reads

`get-concepts` and `get-concept-map` both accept `[ctx]` for tenant-wide reads or
`[ctx filters]`. Supported filters are `:ontology-id`, `:ontology-ids`, `:scope`
and `:broader-uri`. Use only one ontology selector. Both functions apply the
same validation and missing-graph rules.

```clojure
(ontology/get-concept-map ctx {:ontology-id ontology-id :scope :biology})
```

`get-concepts` returns a vector. `get-concept-map` returns a map keyed by
`[ontology-id uri]`, preserving concepts that share a URI across graphs. Empty
selections return `[]` and `{}` respectively.

## Errors and missing resources

| Situation | Result |
| --- | --- |
| Missing ontology or concept lookup | `nil` |
| Revision of a missing ontology | `nil` |
| Ontology existence check | Boolean, or an anomaly for invalid context/input |
| Existing graph with no matching records | Empty collection (`[]`, or `{}` for mappings) |
| Invalid arguments | `:cognitect.anomalies/incorrect` |
| Missing tenant context | `:cognitect.anomalies/forbidden` |
| Explicit collection/search scope, mutation target, or export resource missing | `:cognitect.anomalies/not-found` |
| Stale revision, changed retry payload, or concurrent write | `:cognitect.anomalies/conflict` |

A multi-ontology read returns `not-found` if any selected graph is missing; it
does not return partial results. An empty `:ontology-ids` collection returns
`[]` from `get-concepts` and `{}` from `get-concept-map`. Use either `:ontology-id` or `:ontology-ids`, not both. Unfiltered concept
reads remain tenant-wide. Missing graphs in another tenant follow the same
rules as any other missing graph.

An export revision must be a positive integer; a valid revision that does not
exist returns `not-found`. RDF parsing errors return `incorrect`. Storage
anomalies keep their original categories. Unexpected implementation and query
embedding provider exceptions propagate with their diagnostic information.

## Malli contracts

Every public function carries a `:malli/schema` metadata value describing its
arguments and return value, including all arities, anomaly maps and nullable
lookups. Reusable schemas live under `core` and are registered by Grain’s
`defschemas` macro with namespaced keyword references. Obtain the supported
function contracts from the public Vars:

```clojure
(require '[malli.core :as m]
         '[malli.instrument :as mi])

(def search-schema (m/schema (:malli/schema (meta #'ontology/search))))
(m/validate (first (m/children search-schema))
            [ctx ontology-id {:query-text "plant"}])

;; Optional development-time argument/result checking:
(mi/collect! {:ns 'ai.obney.ontology.interface})
(def instrumentation
  {:data (select-keys (m/function-schemas) ['ai.obney.ontology.interface])})
(mi/instrument! instrumentation)
;; ... use the API ...
(mi/unstrument! instrumentation)
```

Schemas describe valid calls. Instrumentation is opt-in and throws on invalid
arguments before normal anomaly handling runs. Loading the library or collecting
schemas does not enable instrumentation. Graph-dependent checks such as resource
existence, hierarchy validity, revision conflicts and predicate permissions still
run in the operations. Embeddings and model identifiers remain opaque by design;
unusable vectors are ignored during search. Maps remain open except where the
API explicitly rejects unknown fields, such as predicate policies and TBox sections.

## Development

```sh
clojure -M:test
# Full obligation, spec, property and mutation verification:
bash scripts/verify-tests.sh
```

The suite includes public API and algorithm tests, generated mutation sequences
checked against an independent model, and real SQLite persistence, restart,
write-failure and concurrent-write tests. It runs without ORC or a model service.
`test.check` and the SQLite adapter are test-only dependencies.

Run a focused suite or a larger reproducible property campaign:

```sh
clojure -M:test ai.obney.ontology.rdf-test
ONTOLOGY_TEST_SEED=7 ONTOLOGY_TEST_TRIALS=500 clojure -M:test ai.obney.ontology.property-test
clojure -Sdeps '{:paths ["src" "test"]}' -M scripts/mutation_audit.clj
```

The last command checks that existing tests detect six deliberately introduced
regressions using temporary function replacements. It does not modify source
files. See [the test assessment](docs/TEST-ASSESSMENT.md) for measured results
and remaining coverage work, and
[specs/COVERAGE.md](specs/COVERAGE.md) for the characterized structural diagnostic
baseline; structural analysis is not runtime conformance proof.

[The clean-break migration](docs/extraction-migration.md) describes ORC's
integration boundary. [The specification](specs/ontology.allium) is the durable
behavioral contract.

## Benchmarking

For stage-level latency and allocation attribution, including Grain's μ/trace
spans and SQLite writer context, see [contribution profiling](docs/PROFILING.md).

The benchmark harness targets 10,000 and 20,000 concepts on in-memory and SQLite
stores. It records raw latency/allocation samples, GC activity, fixture loading
cost and source/environment metadata. Each case runs in its own JVM with a time
limit; timeouts are retained separately from completed measurements.

```sh
python3 scripts/benchmark.py --output target/benchmarks/my-baseline
```

See [benchmark methodology and workloads](docs/BENCHMARKS.md) for controls,
history-length experiments and interpretation limits. Benchmark dependencies
are confined to the `:bench` alias. The
[initial 10,000/20,000-concept baseline](docs/BENCHMARK-BASELINE.md) records
measured replay/import costs, lookup timeouts and the fixed-size history control.

See [scoped reads](docs/SCOPED-READS.md) for event tags, Grain projections, cache context and concurrency behavior.

Accepted contributions persist [resolved record changes](docs/CONTRIBUTION-DELTAS.md), including explicit relationship removals, rather than repeating the ontology snapshot.

Mutation commands use [incrementally maintained graph indexes](docs/MUTATION-INDEXES.md) for parents, hierarchy, resolution, connected scope and counts.
