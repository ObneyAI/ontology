# Mutation indexes and command work

Grain's graph projection now retains indexes alongside concepts and edges. They
are updated from each accepted event, including contribution upserts/removals;
commands receive the same indexed state when constructing their candidate graph.
The graph read-model version is incremented to rebuild older cached projections.
The event format and public API are unchanged.

| Index | Command use |
| --- | --- |
| Qualified concept → parent edge identities | Remove only the concept's previous parent edges |
| Qualified concept → incident edge identities | Revalidate surviving relationships after endpoint removal |
| Qualified hierarchy source → target reference counts | Traverse maintained adjacency during cycle validation |
| Ontology → connected ontology reference counts | Discover the connected validation scope without scanning edges |
| Ontology/type/normalized label → concept identities | Find exact matching candidates, including alternate labels |
| Ontology/type/embedding model/dimensions → concept identities | Find potentially comparable semantic candidates |
| Ontology → concept URI membership | Known-URI checks, validation membership and constant-time concept count |
| Ontology → outgoing/incident edge membership | Constant-time outgoing edge count and relevant-edge validation |
| Ontology/concept ID → qualified identity | Direct lookup for concept updates |
| Ontology/predicate → edge identities | Update hierarchy classification when joining predicate policy state |

Counts use the cardinality cached by Clojure's persistent sets; they do not
collect or traverse graph records. Hierarchy and connection references are
counted because several predicates or edges may connect the same nodes or
ontologies. Removing one edge must not remove a remaining connection. Metadata
updates to an existing edge do not change membership counts. Concept changes
remove old label/model entries before inserting the new entries.

Import preparation no longer validates the graph separately. All mutations use
the common validation step after applying the proposed event to prior state.
Schema validation remains in contribution preparation. Newly created concepts
add their supplied parents directly; existing concepts remove only the parent
edges found through the index, preserving metadata on retained edges. When
matching is disabled, no resolution candidates are prepared. When enabled,
the command passes the union of exact-label and compatible-embedding buckets to
the existing resolver, preserving canonical selection and match evidence.

Indexes live only in the graph projection. The small point and relationship
projections retain their existing scoped representations. For incoming edges,
joining source predicate policies updates the returned graph's hierarchy index.
Command validation still loads the complete connected component and retains the
existing optimistic concurrency checks.

`indexes_test.clj` checks manual/import updates, replay, SQLite reopen, index
cleanup after parent/label/model changes, exact candidate selection, 300 generated
comparisons against the unfiltered resolver, one validation per import, skipped
parent removal for new concepts, skipped disabled resolution, parallel edge
references and cross-ontology cycle rejection.

This change adds retained index data; it does not establish a reduction in peak
or retained heap. It leaves full-state diff comparison, accumulated schema/RDF
processing and full graph validation in place. Cycle validation still traverses
from each hierarchy source, but no longer rebuilds adjacency. Narrower validation,
more efficient cycle traversal and Grain's cache eviction remain separate work.

## Benchmark evidence

All twelve lookup, ten-concept import and ten-alias resolution cases completed
within the 180-second whole-case budget at 10,000/20,000 concepts on both stores.
See [raw results](../evidence/benchmarks/mutation-indexes/report.md). Each case
used one warmup and three samples in a fresh JVM. The extended test campaign ran
concurrently, so these are smoke measurements, not controlled comparisons or
latency guarantees. Earlier values below come from the contribution-diff run.

| Store | Concepts | Import ms before → now | Import allocated MiB before → now | Setup seconds before → now |
| --- | ---: | ---: | ---: | ---: |
| in-memory | 10000 | 254.3 → 94.5 | 339.2 → 117.4 | 15.3 → 2.5 |
| in-memory | 20000 | 560.1 → 197.2 | 733.9 → 250.0 | 63.3 → 5.6 |
| sqlite | 10000 | 289.5 → 139.7 | 335.6 → 117.3 | 18.3 → 3.6 |
| sqlite | 20000 | 696.0 → 286.5 | 724.9 → 250.2 | 68.5 → 8.7 |

Warm lookup medians remain below one millisecond in these cases. Schema/RDF work,
full-state diffing and full graph validation remain; the event payload is still
the previously implemented resolved delta with original input and provenance.

## Incremental hierarchy validation

Hierarchy validation now uses the accepted event's proposed changes to identify
new direct hierarchical connections. Contribution imports supply relationship
upserts; manual concept changes use the affected parent index; relationship
creation supplies its edge; predicate classification uses the predicate-edge
index. The before/after hierarchy reference counts distinguish new connections
from metadata updates and parallel predicates between the same endpoints.

For each new `A → B` connection, validation searches for `A` from `B` in the
final candidate hierarchy. This catches cycles formed by several additions and
avoids rejecting paths that the same command removes. Concept-only changes,
removals and additional parallel edges require no cycle traversal. The
previously committed graph must be valid; CAS continues to guard the validated
state at append. A reachability search can still visit a large subgraph, but
validation no longer starts a search from every existing hierarchy source.

Other graph validation and diff construction are unchanged. The full suite
passes 100 tests and 712 assertions, including 500 generated comparisons with
an independent whole-graph topological cycle detector. Focused checks cover
cross-ontology cycles, batch additions, simultaneous removals, parallel edges,
newly hierarchical policy classification and traversal-free concept updates.

## Scoped record validation

Required-field and relationship validation now derives its work from the proposed
event. Contribution upserts identify changed records; manual concept creation or
updates identify the concept and any supplied parent edges; explicit relationship
creation identifies its one edge. Description updates therefore validate one
concept and no relationships, and relationship creation validates no unchanged
concepts. All checks use the final candidate state.

Concept removals additionally consult the prior `:concept-edges` index and check
surviving incoming and outgoing relationships. Removing the endpoint and its
relationships together is valid; leaving a dangling relationship is rejected.
Policy changes recheck affected edges through `:predicate-edges`. The graph model
version is now 5 so cached projections rebuild with the new endpoint index.
This index adds retained membership data; reduced allocation during validation
is not a measurement of retained heap reduction.

The full suite passes 104 tests and 733 assertions. New coverage includes
validation work counts, cross-ontology endpoint removal, policy tightening,
atomic rejection of bad fields/endpoints on both stores, and 500 generated
comparisons with independent whole-graph field and endpoint checks. The existing
hierarchy properties and all six mutation checks also pass. Diff construction
still compares the full before/after record maps.
