# Ontology

A Clojure library for tenant-scoped concept graphs, built on Grain's event store.
The public namespace is `ai.obney.ontology.interface`.

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

(ontology/create-concept! ctx ontology-id
  {:uri "urn:biology:plant" :label "Plant"
   :description "A photosynthetic organism." :scope :biology
   :broader ["urn:biology:organism"]})

(ontology/get-concept-by-uri ctx ontology-id "urn:biology:plant")
(ontology/get-concepts ctx {:ontology-id ontology-id})
(ontology/search ctx {:ontology-id ontology-id :query-text "organism" :limit 10})

;; Stop the store when its application lifecycle ends.
(es/stop store)
```

Commands return result maps, including graph revision and generated identities,
or a Cognitect anomaly map. Check `:cognitect.anomalies/category` before using a
result. Concept lookup and search require tenant context and explicit graph
identity; tenant-wide administrative enumeration is available through
`list-ontologies` and unfiltered `get-concepts`. The tenant
context comes from the host's authenticated application boundary; the library
does not provide authentication.

`update-concept!` takes context, ontology ID, concept ID and a changes map.
The concept ID is returned by creation as `:concept-id` and read as `:id`.
Mutable fields include labels, descriptions, parents, indicators, attributes and
optional embeddings. Parent updates maintain the graph's hierarchy views.

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
  {:ontology-id ontology-id
   :contribution-id (random-uuid)
   :expected-revision (ontology/get-revision ctx ontology-id)
   :source {:source-id (random-uuid)
            :content-hash "sha256-of-the-source-bytes"
            :producer {:name "catalog-parser" :version "1"}}
   :concepts [{:uri "urn:biology:oak" :label "Oak"
               :description "A tree in the genus Quercus." :scope :biology
               :broader ["urn:biology:plant"]}]
   :relationships []
   :tbox {:classes [] :object-properties [] :datatype-properties []}
   :abox []})

(ontology/import-contribution! ctx request)
```

The contribution ID is the idempotency key. Retry the identical request to
recover its original result. Reusing its ID with changed content is a conflict;
a new contribution with a stale expected revision is also a conflict. A new
contribution ID may interpret the same source bytes again. Imports use additive
upserts: omission does not delete existing knowledge. Supplied parent lists
replace that concept's parent list.

`:resolution-policy {:match-labels? true :similarity-threshold 0.85}` enables
normalized-label resolution and optional matching of supplied embeddings.
Existing concept identities are preferred as canonical. Producer metadata and
source references are data; the library does not execute or inspect a producer.

`import-rdf!` accepts the same source, contribution ID and revision envelope,
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

Search combines lexical matches, optional graph seeds and optional embeddings.
Pass `:seed-uris` for graph traversal. An optional `:embed-query` function and
`:embedding-model` identify the query embedding provider; it is supplied by the
host, not loaded from an orchestration component.

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
