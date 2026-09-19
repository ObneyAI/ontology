# Contribution latency tracing

Ontology stages emit `com.brunobonacci.mulog/trace` spans in the same trace tree as Grain. No new dependency is needed: μ/log 0.9.0 is already supplied by the pinned Grain dependency. A host can collect these spans with its normal μ/log publisher.

The dedicated profiler adds complete Grain call boundaries, collects native Grain spans, and samples thread allocation. Run each case in a fresh JVM:

```sh
clojure -M:bench:profile --sizes 20000 --backends in-memory --samples 5 --warmup 2 --output target/profile/memory-20000
clojure -M:bench:profile --sizes 20000 --backends sqlite --samples 5 --warmup 2 --output target/profile/sqlite-20000
```

Use `--sizes 10000` for the smaller case. `--operations import-resolved` measures label resolution instead of adding ten concepts. Only one size, backend and operation are accepted per invocation. Benchmark options also control batch size, dimensions, history and contribution size.

Each run populates the fixture, executes warmups and control samples, then captures the measured commands. Setup is outside the root spans. For the default two warmups and five controls, the first profiled add-ten command starts with 70 more concepts than the initial dataset. Controls include the production ontology spans but omit the collector, allocation counters and profiling-only wrappers. Control then profile is a fixed order, so their difference is not an unbiased measurement of instrumentation overhead.

The `.edn` artifact contains individual spans, trace IDs and parent links, per-command elapsed time/allocation/GC observations, options, dependency pins and source fingerprints. The `.md` report contains per-stage medians. Export uses an explicit whitelist; command payloads, graph records and exception messages from native Grain events are not included in the artifact. Other host publishers retain their own policies.

## Boundaries

Production ontology spans cover request/record hashing, retry reads, state loading, contribution preparation, schema/RDF preparation, diff construction, event/delta application, index maintenance, and graph/hierarchy validation.

The isolated profiler additionally wraps:

- Grain's full projection call, including work outside its native cache spans.
- Event construction and the full append call, including validation before adapter append.
- SQLite reducible consumption, including query execution, decoding and downstream reduction. Returned-row counts are captured; constructing a lazy read alone is not timed as a database read.
- SQLite writer execution, propagating the caller's μ/log context through the coordinator. Native append and transaction spans then join the command tree. The native append span also retains `:queue-wait-ns`.

Grain's pinned SQLite writer does not propagate this context itself. The profiling harness temporarily passes a forwarding executor in a copy of the event-store record, delegating to the actual coordinator and queue. It waits for writer tracing to finish before closing the enclosing span. It also temporarily wraps the pinned SQLite reducible factories. These adapters and `with-redefs` are restricted to an isolated, single-client profiling JVM; they are not installed in ordinary application execution and do not modify the Grain checkout.

## Accounting and limits

Inclusive time contains descendants. Self time subtracts direct children, including the synchronous command's blocking wait for its SQLite writer. This accounting is for this single-client, sequential workload, not overlapping asynchronous branches. Each sample must contain a complete tree, unique trace IDs and nonnegative self time. Native Grain spans must be present. Collection timeout is a failure.

Allocation subtraction follows the nearest measured ancestor **on the same thread**. Native Grain spans do not supply allocation counters, so their allocation stays in their enclosing measured span. SQLite writer allocation is separate from caller-thread allocation. These counters measure bytes allocated, including instrumentation overhead; they do not measure retained heap or peak memory. Allocation fields are absent on runtimes without the JVM counter. Exceptions preserve μ/log's error outcome and the original exception behavior.

Rows report medians of per-sample totals for each event name. Inclusive rows must not be added. Even self-time medians need not sum exactly to the root median, because stage medians can come from different samples. Use an individual sample's tree for exact accounting.

The synthetic fixture has a binary-tree hierarchy, embeddings, and no schema, instances or raw RDF additions. Small schema/RDF time on this fixture says nothing about schema-heavy imports. Five samples identify large costs; they are not a tail-latency or concurrency study.

See [recorded profiles](../evidence/profiles/import-stages/README.md) for the 10k and 20k measurements on both stores. Repository evidence keeps one summary and one consolidated `results.edn` containing all four runs, plus the verification log. Individual generated reports remain local under ignored `target/`.

## Retry query comparison at 20k

A diagnostic comparison on the same SQLite fixture used three warmups and 15
samples per variant, alternating execution order. It compared the existing
ontology-plus-request tag query with the request tag alone. Both variants used
Grain's existing query builder and returned identical events. Cold read-model
measurements used a unique projection scope per invocation to prevent L1 reuse.

| Scenario | Path | Two tags, median ms | Request tag only, median ms |
|---|---|---:|---:|
| New request (no event) | Consumed SQLite query | 58.70 | 0.16 |
| New request (no event) | Grain cold read model | 57.22 | 0.42 |
| Accepted request | Consumed SQLite query | 73.65 | 13.77 |
| Accepted request | Grain cold read model | 74.05 | 13.78 |

The accepted request here imported 1,000 concepts during fixture setup, so its
remaining latency is not representative of retrying a ten-concept contribution.
This experiment measures the lookup, not the whole import command.

`EXPLAIN QUERY PLAN` shows indexed tag/event lookups and temporary B-trees for
`GROUP BY` and `count(DISTINCT)` in both variants. The two-tag query admits 21
candidate events even when the request is absent; request-only admits zero.
For the accepted request, the respective candidate counts are 21 and one.
Thus the broad ontology tag adds work before the `HAVING` clause can reject
nonmatching events. The fixture has 20,000 concepts across 20 contribution
events plus ontology creation, not 20,000 separate concept events.

The request tag already derives from ontology ID and request ID, and Grain
adds tenant scope to the query. These results support retaining the initial
idempotency check and its read model while removing the redundant ontology tag.
The production lookup was unchanged for this comparison. Raw observations,
query plans and the diagnostic script remain local under ignored
`target/retry-query-comparison/`.

The production lookup now uses the request tag alone. A subsequent five-sample
20k SQLite profile measured 0.420 ms for retry lookup and 140.71 ms for the whole
ten-concept import. Grain's request read model, retry behavior and append CAS
are retained. The follow-up is archived with the four baseline runs under
`"sqlite-20000-request-only"` in the existing consolidated profile results.

After incremental hierarchy validation, five-sample 20k profiles measured
0.089 ms (memory) and 0.097 ms (SQLite) for the hierarchy stage, with about
0.103 MiB allocated by that stage. Whole-command medians were 50.65 ms and
66.62 ms respectively. Both follow-ups are in the same consolidated results
file under the `*-20000-incremental-hierarchy` keys. Other graph validation and
full-state diff construction now dominate these fixtures.

After scoped concept/relationship validation, five-sample 20k profiles measured
0.040 ms (memory) and 0.036 ms (SQLite) of graph-validation self time, excluding
hierarchy checks. Whole-command medians were 21.86 ms and 27.87 ms. Diffing now
accounts for the largest stage at 18.32/19.75 ms. Both runs are retained in the
same consolidated results file under the `*-20000-scoped-validation` keys.

After tracked contribution diffing, five-sample 20k profiles measured
0.033 ms (memory) and 0.036 ms (SQLite) for the diff stage, with 0.020 MiB
allocated per diff. Whole-command medians were 3.24 ms and 5.76 ms. The existing
consolidated results retain these runs under `*-20000-tracked-diff` keys.
Request/record hashing and SQLite persistence now exceed the diff cost.
