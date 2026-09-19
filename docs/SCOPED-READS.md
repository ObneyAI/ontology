# Scoped ontology reads

Events identify affected ontologies, qualified concepts, concept IDs, requests,
contributions, sources and source versions. Relationship changes also identify
an ontology relationship stream and both old and new endpoints. Concept and
request tags use deterministic UUIDs because Grain tags require UUID identities.
Contribution payloads contain resolved record changes and explicit removals.
The same change set determines concept and relationship stream tags; unchanged
graph records are omitted. See [contribution changes](CONTRIBUTION-DELTAS.md).

Registered Grain read models serve graph state, metadata/revisions, predicate
policies, contribution history, requests, individual concepts and relationships.
The integration calls Grain's projection processor with its L1 cache and event
watermarks. Every current read revalidates its stream; repeat reads consume only
new matching events. A host may provide `:cache` for Grain's L2 storage. Otherwise
L2 writes use a sink and L1 still works. Cache keys include the store instance,
tenant, complete query and projection options. Hosts that need L2 continuity
across store restarts may provide a stable, globally unique `:ontology/store-id`.
Changing the underlying store requires changing that ID.

| Operation | Events selected |
| --- | --- |
| Concept lookup | Qualified concept stream plus originating ontology predicate policies |
| Ontology metadata/revision | Requested ontology stream |
| Concept listing/search/current export | Requested ontology streams plus external originating policies |
| Relationships | Relationship changes for the requested ontology, plus originating policies |
| Sources/contributions/mappings | Contribution events for the requested ontology |
| Committed retry | Qualified request stream |
| Historical export | Ontology events through the revision watermark and policies through the same bound |

Bulk concept views construct relationship adjacency once, then sort qualified
identities directly. Point views retain only the requested concept and its
incident edges. Incoming cross-ontology relationships use the source ontology's
predicate policy when constructing inverse and symmetric views.

The graph projection maintains [mutation indexes](MUTATION-INDEXES.md) for parent
edges, hierarchy neighbors, matching candidates, connection discovery and counts.

Mutations project the complete connected ontology component needed for endpoint
and cycle validation. A head watermark captured before validation bounds the
optimistic concurrency check to newly appended events. Independent ontologies
use separate CAS streams. Connected multi-ontology mutations conservatively
check the tenant suffix because Grain CAS accepts one query, not a union of
streams. This can reject a concurrent unrelated write; it preserves validation
correctness. CAS requests at most one event instead of counting the full history.

Historical projection bounds are enforced by the adapter because Grain adds an
`:after` cursor while its event-store contract prohibits combining that cursor
with `:as-of`. A cached projection already at its bound needs no further event
read. Scoped updates are serialized per cache stripe to prevent an older reader
from replacing a newer projection watermark.

`structural_test.clj` observes consumed events and actual queries, including
cold/warm lookup, removed parents, request-only retry, store isolation, source
policies, independent writes and historical bounds on in-memory and SQLite.
Existing tests continue to cover competing writes, replay, durable recovery,
mutation failures and graph semantics. The benchmark `projection-replay` case
explicitly replays raw events, preserving its meaning after normal reads gained
caching.

These streams require newly tagged events. There is no compatibility layer for
older untagged histories or the previous snapshot contribution format.

## Verification and measured limits

The complete suite passes 91 tests and 656 assertions. The additional seed-7
campaign passes all 3,000 generated trials, including cache eviction pressure.
All six deliberate regressions remain detected. Allium has 109 mapped
obligations (96 generated, 13 prose), with no errors or process findings.

The earlier, pre-diff [benchmark evidence](../evidence/benchmarks/structural-reads/report.md) records
warm lookup medians of 0.73/0.54 ms in memory and 0.91/0.90 ms in SQLite at
10,000/20,000 concepts. These are three-sample smoke measurements after one
warmup, not latency guarantees. The property campaign ran concurrently, so these
are not controlled comparisons against the original baseline. Setup took
23–99 seconds in that run, which still used full contribution snapshots.

The extended property campaign exposed a separate Grain cache eviction cost:
the pinned L1 implementation sorts its entire cache on each insertion beyond its
10,000-entry capacity. An isolated run inserting 20,000 empty entries took
23.8 seconds, compared with 25.5 ms for 10,002 entries. This occurs without
ontology events or contribution payloads. Cache churn is a remaining dependency
performance issue; the scoped stream changes do not fix Grain's eviction
algorithm. Reproduce using `clojure -M evidence/grain-cache-eviction.clj`.
