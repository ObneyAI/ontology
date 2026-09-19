# Review: concept and relationship view construction

A single URI lookup currently materializes every concept in the tenant before
selecting one result. During that materialization, each concept independently
rebuilds, deduplicates and sorts its graph's relationship views. This explains
substantial avoidable work independently of event replay.

## Confirmed execution path

1. `interface/get-concept-by-uri` calls `get-concepts` and selects a URI from its
   result (`interface.clj:66`). The collection is eagerly materialized first;
   `first` does not short-circuit the expensive construction.
2. `get-concepts` calls `state/concepts` before applying ontology and scope filters
   (`interface.clj:57`). Unrelated graphs in the same tenant are also materialized.
3. `state/concepts` calls `relationships` inside its per-concept loop
   (`state.clj:153`). Every call scans stored edges, generates inverse/symmetric
   views, deduplicates them and sorts the result. It then scans that complete
   relationship vector to enrich one concept.
4. Relationship sorting uses `(sort-by (comp pr-str edge-key))`
   (`state.clj:151`). The sort comparator recomputes this serialization while
   comparing elements. Concept ordering also serializes identity keys while
   sorting. Repeating these sorts for each concept multiplies temporary string
   allocation.

## Instrumented evidence

The review used an immutable, prebuilt tenant state so event-store reading and
replay were excluded. Temporary function wrappers counted construction and
printing calls. This is a structural diagnostic, not an additional latency
benchmark or a sampled CPU attribution profile.

| Requested graph | Other graph in tenant | Lookup | Concept views built | Relationship view builds | `pr-str` calls |
| ---: | ---: | --- | ---: | ---: | ---: |
| 100 concepts | None | Existing URI | 100 | 100 | 250,878 |
| 100 concepts | None | Missing URI | 100 | 100 | 250,878 |
| 1 concept | 100 concepts | Existing URI in the one-concept graph | 101 | 101 | 253,500 |

[Diagnostic source](../evidence/concept-view-review.clj) and
[raw counters](../evidence/concept-view-review.edn). Reproduce with:

```sh
clojure -M evidence/concept-view-review.clj
```

The [benchmark baseline](BENCHMARK-BASELINE.md) independently measured a
100-concept lookup at 708 ms and 942 MiB of calling-thread allocations. The
counters identify redundant work; they do not attribute a precise fraction of
that time or allocation to any individual function.

For one graph with N concepts and E edges, view construction roughly costs
O(N × E log E), plus concept ordering. With multiple graphs, each repeated
relationship build also scans the tenant's full stored edge collection. Missing
URI lookup has the same construction cost as a successful lookup.

## Recommended change boundary

Keep query-view construction within one reconstructed state:

- Normalize the requested ontology ID, then retrieve a raw concept directly from
  the existing `[:concepts [ontology-id uri]]` map. Return nil immediately for a
  missing key. Enrich only the matched concept.
- Apply ontology and raw-field filters before constructing bulk concept views.
  Parent filtering must still use the derived hierarchy or its adjacency index.
- Derive logical relationship views once for the requested scope, and build
  outgoing/incoming indexes keyed by qualified `[ontology-id uri]` identity.
  Bulk enrichment then visits each relevant edge once instead of scanning the
  entire edge collection for each concept.
- Keep deterministic sorting at collection-return boundaries. Internal
  adjacency sets do not require relationship sorting. If retaining current
  printed-key ordering, compute each printed sort key once per item.

After state reconstruction, a point lookup can become a direct concept-map
lookup plus one edge pass to enrich the result. Bulk construction can approach
O(E + N), followed by any required output sorting. Building a query-local index
still costs an edge scan; O(1) or degree-only end-to-end point lookup would
require an index already present in the reconstructed state. Event replay is a
separate cost and remains outside this first change boundary.

There is no need for a persistent cache or a storage migration to remove the
per-concept relationship reconstruction. Query-local derived indexes avoid
introducing cache invalidation or a second revision lifecycle.

## Behavior and verification requirements

Preserve normalized tenant/ontology/URI identity, including equal URIs in separate
graphs; hierarchy parent replacement; narrower views; related sets; custom
inverse and symmetric policies; logical-edge deduplication; source-graph policy
resolution for cross-graph edges; qualified outgoing relation targets; and
metadata/timestamps on public relationship results. Preserve deterministic
collection ordering and snapshot content/checksums.

The current suite already exercises these semantics through public APIs,
resolution imports, historical snapshots and fresh-store replay. An implementation
change should additionally verify that single-item lookup matches the equivalent
item in bulk results, unknown URIs avoid hydration, scoped requests do not hydrate
unrelated graphs, and relationship derivation is bounded per scope rather than
per concept. Run the suite and the same lookup, search, enumeration and export
benchmarks after the change.

This review changed no production implementation.
