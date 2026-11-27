# Using Ontology

## Create and search a graph

Add the [dependency](../README.md#getting-started), then start a REPL with `clojure`. This example uses an in-memory Grain event store. In an application, take the tenant ID from the authenticated request.

```clojure
(require '[ai.obney.grain.event-store-v3.interface :as es]
         '[ai.obney.ontology.interface :as ontology])

(def store (es/start {:conn {:type :in-memory}}))
(def ctx {:event-store store :tenant-id (random-uuid)})
(def graph-id (:ontology-id (ontology/create-ontology! ctx {:name "Biology"})))

(ontology/create-concept! ctx graph-id
  {:uri "urn:biology:organism" :label "Organism" :description "An individual living system."})

(def plant
  (ontology/create-concept! ctx graph-id
    {:uri "urn:biology:plant" :label "Plant" :description "A photosynthetic organism."
     :broader ["urn:biology:organism"]}))

(mapv :label (ontology/search ctx graph-id {:query-text "photosynthetic"}))
;; => ["Plant"]
```

Create parents before referring to them by URI. Use `get-concept-by-uri` for an exact lookup. Search can combine text, graph traversal, and embeddings supplied by the application. The public API documents the options and Malli schemas for search, imports, entity resolution, and snapshots. [API reference](../src/ai/obney/ontology/interface.clj)

## Writes, errors, and retries

A new graph starts at revision 1. Successful writes return the committed revision and identifiers. Expected failures return a map with `:cognitect.anomalies/category` and `:cognitect.anomalies/message`. Check for an anomaly before using a result, including the result of `ontology-exists?`. A missing concept lookup returns `nil`.

To update the plant only if the graph is still at the revision you read, capture the revision and keep the request for retries.

```clojure
(def update-request
  {:changes {:description "A member of the kingdom Plantae."}
   :expected-revision (ontology/get-revision ctx graph-id)
   :request-id (random-uuid)})

(ontology/update-concept! ctx graph-id (:concept-id plant) update-request)
```

Repeating a committed request with the same key and payload returns its original result. A changed payload or stale revision returns a conflict. Every commit checks for concurrent writes, even when the caller omits `:expected-revision`.

Manual writes accept an optional UUID `:request-id`. Creation keys are tenant-scoped. Other keys are graph-scoped. Imports require a UUID `:contribution-id` as their retry key and reject `:request-id`. A new contribution ID can record a different interpretation of the same source bytes.

## Delete, merge, and rename concepts

Each operation takes the ontology ID, a concept UUID and a request map. These examples show independent calls.

```clojure
(ontology/rename-concept! ctx graph-id concept-id {:new-uri "urn:biology:plantae"})
(ontology/merge-concepts! ctx graph-id duplicate-id {:target-concept-id plant-id})
(ontology/delete-concept! ctx graph-id concept-id {})
```

A rename accepts an unused URI reference and keeps the UUID, label and relationships. A concept appearing in an RDF triple requires an absolute destination URI. A merge keeps the target UUID, URI and descriptive fields. The target receives the source label and alternate labels, with duplicates and the target's primary label omitted. Redirected links retain their IDs and metadata. An existing target link wins a collision, including one visible through an inverse or symmetric predicate. A merge drops newly formed self-links and rejects a hierarchy cycle.

Delete removes the concept and its incident links. Each ontology whose relationships change advances one revision in the same commit. Current lookups of a retired UUID or URI return `nil`. A later creation or import can reuse the URI with a fresh UUID. Earlier snapshots and original contribution records remain available.

RDF subject and object URIs follow the edit. A rename or merge also updates schema domain, range, datatype and individual type references that rely on the concept. Deleting the only definition of a referenced type returns a conflict. Separately declared TBox classes and built-in types retain their own meanings. Existing import mappings follow the surviving concept and are removed when it is deleted.

All three operations accept `:request-id` and `:expected-revision` with the retry behavior described above.

## Close the store

The application owns the store. Close it when finished with the example.

```clojure
(es/stop store)
```

## Persistent read cache

For a persistent event store, an optional Grain KV cache lets projections resume from their saved event positions across restarts. To use LMDB, start the JVM with the module access it requires.

```sh
clojure -J--add-opens=java.base/java.nio=ALL-UNNAMED \
        -J--add-exports=java.base/sun.nio.ch=ALL-UNNAMED
```

```clojure
(require '[ai.obney.grain.kv-store.interface :as kv]
         '[ai.obney.grain.kv-store-lmdb.interface :as lmdb])

(def cache
  (kv/start (lmdb/->KV-Store-LMDB {:storage-dir "target/ontology-cache"
                                    :db-name "read-models"
                                    :map-size (* 64 1024 1024)})))
```

Pass the started cache as `:cache` and a stable `:ontology/store-id` alongside the event store and tenant ID. Keep the store ID across restarts of the same event store and change it when replacing that store. Omitting the ID isolates the cache to the current event store instance. Stop the cache with `(kv/stop cache)` at shutdown.

## Commit graph and application events together

Use `ai.obney.ontology.grain/prepare-vocabulary` when a host command must commit a graph change and its own events atomically. Supply an `:ontology` definition with a stable `:request-id`, a vector of `:concepts`, and a vector of `:relationships` with endpoints identified by URI.

On success, append `:command-result/events` and the host events in one Grain append using the returned `:command-result/cas` as the append’s `:cas`. After a conflict, prepare again against the current state. The ontology ID is in `:command-result/data`. [Grain integration](../src/ai/obney/ontology/grain.clj) · [Tested example](../test/ai/obney/ontology/grain_test.clj)
