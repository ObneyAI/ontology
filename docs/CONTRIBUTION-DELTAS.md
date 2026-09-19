# Resolved contribution changes

`import-contribution` resolves the submitted knowledge, merges it with existing
state and validates the candidate graph. It then diffs the previous and candidate
states. One atomic `contribution-imported` event records those accepted changes.

The event retains contribution/source identity, original input, canonical mappings,
resolution evidence, timestamp, request hash and committed result. Its `:changes`
contains:

| Collection | Upsert/removal identity |
| --- | --- |
| `:concepts` | `[ontology-id uri]` |
| `:relationships` | `[source-ontology-id source-uri predicate target-ontology-id target-uri]` |
| `:tbox` | Section, then record URI |
| `:abox` | Record URI |
| `:rdf-triples` | Complete triple; additions/removals |

Record deltas have `:upserts` maps and `:removals` vectors. An upsert contains the
complete resolved record and replaces the prior record, including field clearing.
Whole schema sections can be removed through `:tbox-removals`. This representation
does not add deletion operations to the public contribution API: imports retain
their existing additive/upsert semantics, with explicit removal of obsolete
relationships when replacing parents. Concept hierarchy fields do not independently
recreate edges during replay; relationship changes are authoritative.

Schema and instance collections use URI identity and deterministic URI ordering;
RDF triples use set identity and deterministic serialization ordering. Diffs are
computed after canonical references have been resolved. Replay applies accepted
changes without rerunning resolution or validation. Graph, point and relationship
projections use these changes, while history retains the original submitted input.
Stream tags derive from the same concept and relationship changes, including the
endpoints of removed edges.

An accepted contribution with no graph changes still records source evidence,
idempotency result and a revision. A retry with the same contribution ID and input
returns the original result without another event. Timestamp or provenance changes
to an existing concept count as record changes even if its label is unchanged.

The event schema and affected read-model versions have changed. There is no reader
for the former snapshot event format, as backward compatibility was explicitly
excluded from this port.

Verification includes 500 generated before/after pairs covering record additions,
replacement, field clearing, removal, schema section removal and RDF differences.
Public API tests verify that a one-concept update to 1,000 concepts stores one
concept record, reconstructs the resulting graph, preserves history and retries,
and updates both endpoints when a parent changes. Existing SQLite reopen,
fresh-process replay, historical export, resolution and concurrency tests exercise
the new format as well.

This removes repeated unchanged graph records from persisted events. The original
request is still retained, and a changed concept still includes its complete record
(including an embedding, if present). Diff computation and whole-graph validation
remain proportional to existing state; this does not eliminate command-side graph
scans or Grain's cache eviction overhead.

## Measurements before mutation indexes

All eight lookup/import cases completed within the 180-second whole-case budget
at 10,000 and 20,000 concepts on in-memory and SQLite stores. The full suite
passes 84 tests and 609 assertions, including 500 generated diff checks. The
additional 3,000-trial campaign and all six deliberate-regression checks pass.
Allium reports 109 mapped obligations and no errors or process findings.

[Raw benchmark results](../evidence/benchmarks/contribution-deltas/report.md)
use one warmup and three samples. Import measurements add ten concepts per call.
The property campaign ran concurrently for part of this run, so these are smoke
measurements, not controlled performance comparisons or latency guarantees.

| Store | Concepts | Warm lookup ms | Import ten concepts ms | Lookup-case database/WAL bytes |
| --- | ---: | ---: | ---: | ---: |
| in-memory | 10000 | 0.772 | 254.335 | n/a |
| in-memory | 20000 | 0.570 | 560.137 | n/a |
| sqlite | 10000 | 0.769 | 289.460 | 32265096 |
| sqlite | 20000 | 0.719 | 695.984 | 60695440 |

At 20,000 concepts, SQLite database/WAL bytes measured after lookup setup fell
from 212,968,232 in the earlier snapshot run to 60,695,440 in this run. This is
storage size including WAL, not heap or cumulative allocation. Whole-graph
validation and diff scans remain visible in import costs.

Subsequent [mutation indexes](MUTATION-INDEXES.md) remove repeated parent-edge scans, resolution preparation and duplicate graph validation. The measurements above precede that change.

## Tracked concept and relationship diffs

Contribution preparation now starts a candidate-local set of touched identities.
`put-concept`, `put-edge` and `remove-edge` record their qualified keys while
building the candidate. Edge keys are recorded after canonicalization, and parent
replacement records both removed and inserted edges. The tracker is a persistent
value carried with the candidate, with no process-global or thread-local state.
It is not included in emitted events or cached read models.

Diff construction selects only those keys from the original and final concept
and edge maps. The existing record comparator then emits their net upserts and
removals. Repeated edits, unchanged upserts, and remove/reinsert operations that
restore the original value produce no spurious changes. Final timestamp or
provenance differences still count as changes. Untracked snapshot callers retain
the complete comparison path used by diagnostic and property checks.

Schema, instance and RDF comparisons retain their previous semantics; this change
removes the whole concept/relationship map scans. The full suite passes 107 tests
and 744 assertions, including 500 generated tracked mutation sequences compared
with complete diffs and replay. A command-level test verifies that importing ten
concepts into an existing graph supplies only ten concept and ten edge identities
to those comparisons, and that tracking data is absent from events/read models.
