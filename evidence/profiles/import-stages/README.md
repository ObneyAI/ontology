# Import stage attribution

Five profiled commands per case, after two warmups and five control commands, each adding ten concepts. Each case used a fresh JVM. Dataset construction is excluded. The initial graph has 10,000 or 20,000 concepts; the first profiled command starts at initial size + 70.

| Measured stage (ms) | Memory 10k | SQLite 10k | Memory 20k | SQLite 20k |
|---|---:|---:|---:|---:|
| Whole command | 58.13 | 93.89 | 138.39 | 206.81 |
| Hierarchy validation | 33.00 | 37.24 | 80.06 | 84.98 |
| Other graph validation (self time) | 12.11 | 13.97 | 30.31 | 30.50 |
| Diff construction | 8.71 | 10.35 | 21.26 | 25.11 |

At 20k, SQLite's retry/idempotency lookup takes **58.74 ms**. The sample-0 query returns **zero rows** but spends **59.71 ms** consuming its reducible. The other reads in that sample return one event for the watermark, one event to catch up the projection, and zero for CAS. Returned-row count does not measure how many rows SQLite examines internally; this trace identifies the expensive operation without asserting a query-plan cause.

The SQLite write transaction is **1.77 ms** inclusive at 20k, with full append **2.34 ms**. Schema/RDF preparation is **0.03 ms** on this schema-empty fixture. Hierarchy validation allocates approximately **176 MiB per command** on the caller thread, other graph validation approximately **30 MiB**, and diff construction approximately **42 MiB**. These are allocated bytes, not retained heap.

Every value is a median unless a sample is explicitly identified. Stage medians need not sum to the root median. The [consolidated results](results.edn), keyed by backend and size, retain stage summaries, raw trees, allocation counters, GC observations, queue wait, query result counts and source fingerprints. See [methodology and reproduction](../../../docs/PROFILING.md).

The controls versus profiled medians at 20k were 125.81/138.39 ms in memory and 202.44/206.81 ms on SQLite. These ordered measurements are diagnostic, not a claim of performance improvement over earlier benchmark runs or an unbiased overhead estimate.

Validation: [95 tests, 683 assertions, zero failures/errors](tests.log). Added tests check nested time accounting, thread-specific allocation accounting, payload filtering, return/exception preservation, and native Grain/SQLite trace integration.

## Request-only retry lookup

After removing the redundant ontology tag from `read-request`, the same 20k
SQLite workload (two warmups, five controls, five profiled ten-concept imports)
measured **0.420 ms** for retry lookup and **140.71 ms** for the whole command,
compared with **58.74 ms** and **206.81 ms** in the earlier profile. These are
separate JVM runs; the paired query experiment in the profiling documentation
isolates the query-shape effect. Hierarchy validation remains the largest stage
at **78.55 ms**. CAS and the initial idempotency check remain in place.

Raw spans and metadata are stored under `"sqlite-20000-request-only"` in the
existing [consolidated results](results.edn). The updated [full suite](../../rigorous-current.log)
passes 96 tests and 701 assertions, including same-request-ID isolation across
ontologies and tenants on both memory and SQLite stores.

## Incremental hierarchy validation

Five-sample 20k profiles after checking only newly added hierarchical connections:

| Stage | Memory ms | SQLite ms |
|---|---:|---:|
| Hierarchy validation | 0.089 | 0.097 |
| Other graph validation (self) | 26.074 | 32.347 |
| Diff construction | 18.636 | 23.541 |
| Whole command | 50.65 | 66.62 |

Hierarchy validation allocates about 0.103 MiB per command on both stores,
versus roughly 176 MiB in the earlier full-hierarchy traversal. The preceding
SQLite request-only profile measured 78.55 ms for hierarchy validation and
140.71 ms for the whole command. These are separate JVM runs with the same
fixture and sampling configuration; remaining stage timings also vary.

The existing consolidated EDN contains these runs under
`"in-memory-20000-incremental-hierarchy"` and
`"sqlite-20000-incremental-hierarchy"`, including raw spans and source hashes.
The [full suite](../../rigorous-current.log) passes 100 tests and 712 assertions;
[all six deliberate regressions](../../mutation-audit.log) were detected.

## Original baseline versus current imports

Each operation imports ten concepts into the indicated initial graph size.
Latency and calling-thread allocation are medians per command.

| Store / concepts | Original ms | Current ms | Original allocated MiB | Current allocated MiB |
|---|---:|---:|---:|---:|
| Memory / 10,000 | 1,192.58 | 26.61 | 1,670.35 | 37.93 |
| SQLite / 10,000 | 2,415.80 | 30.51 | 2,457.38 | 38.45 |
| Memory / 20,000 | 2,905.61 | 50.65 | 4,095.06 | 76.44 |
| SQLite / 20,000 | Not measured | 66.62 | Not measured | 76.40 |

The original SQLite 20k import case timed out during setup. It provides no
operation latency or allocation baseline. Original cases used three samples,
one warmup and a 2 GiB heap cap; current profiles use five samples, two warmups,
five preceding controls and an 8 GiB heap cap, plus tracing. These are historical
observations rather than a controlled same-configuration speedup experiment.
Allocation is cumulative calling-thread allocation, not retained or peak heap;
SQLite worker allocation is separate. Sources: [initial campaign](../../benchmarks/baseline-10k-20k/report.md)
and this consolidated profile archive. Current 10k runs are stored under
`"in-memory-10000-incremental-hierarchy"` and
`"sqlite-10000-incremental-hierarchy"`.

## Scoped concept and relationship validation

Five-sample profiles of ten-concept imports at 20k after limiting record checks
to changes and their dependencies:

| Metric | Memory before | Memory after | SQLite before | SQLite after |
|---|---:|---:|---:|---:|
| Record validation self time, ms | 26.074 | 0.040 | 32.347 | 0.036 |
| Record validation self allocation, MiB | 30.570 | 0.019 | 30.095 | 0.018 |
| Whole command, ms | 50.65 | 21.86 | 66.62 | 27.87 |

The before values are the incremental-hierarchy profiles. These are separate
JVM runs with matching workload/sampling options, not paired observations.
The new endpoint index retains additional membership data; allocation counters
do not measure that retained heap. Diff construction now dominates, at
18.32 ms in memory and 19.75 ms on SQLite.

The same consolidated EDN stores these runs as
`"in-memory-20000-scoped-validation"` and `"sqlite-20000-scoped-validation"`.
The full suite passes 104 tests and 733 assertions; all six deliberate regressions
were detected. Raw per-case output remains local under ignored `target/`.

## Tracked contribution diffs

Five-sample 20k profiles after tracking touched concept/relationship identities:

| Metric | Memory before | Memory after | SQLite before | SQLite after |
|---|---:|---:|---:|---:|
| Diff time, ms | 18.320 | 0.033 | 19.754 | 0.036 |
| Diff allocation, MiB | 41.880 | 0.020 | 41.944 | 0.020 |
| Whole command, ms | 21.86 | 3.24 | 27.87 | 5.76 |

Before values are the scoped-validation profiles. Both new cases used separate
fresh JVMs, the same 20k binary-tree fixture and ten-concept increments, two
warmups and five control commands before five profiled commands. Their control
medians without the extra profiling wrappers/collector were 2.62 ms (memory)
and 4.94 ms (SQLite). These historical comparisons are not paired observations.

The existing consolidated results contain both new runs under
`"in-memory-20000-tracked-diff"` and `"sqlite-20000-tracked-diff"`, including
raw traces, allocation samples and source hashes. Schema/instance/RDF diff
semantics remain unchanged. The full suite passes 107 tests and 744 assertions,
and all six deliberate regressions were detected.
