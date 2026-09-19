# Initial ontology performance baseline

The standalone ontology currently has substantial scaling costs. URI lookup is
already expensive at 100 concepts, and the 1,000/10,000/20,000-concept lookup
cases did not complete within their case budgets. Replay and incremental writes
also grow markedly with graph size and retained event history.

Measurements were taken on the development machine with a 2 GiB maximum JVM
heap, one client, a binary-tree graph, 32-dimensional synthetic embeddings,
1,000-concept loading batches, one warmup and three measured calls per case.
All cases ran sequentially in separate JVMs. These are preliminary medians and
ranges, not p95/p99 estimates or production capacity guarantees. Exact JVM/OS,
source hashes, dependency coordinates, commands and raw samples are archived
alongside every result.

## Completed large-graph measurements

| Backend | Initial concepts | Replay median | Import ten concepts median | Replay allocated MiB/call |
| --- | ---: | ---: | ---: | ---: |
| In-memory | 10,000 | 99 ms | 1,193 ms | 305 |
| In-memory | 20,000 | 379 ms | 2,906 ms | 1,191 |
| SQLite | 10,000 | 563 ms | 2,416 ms | 925 |
| SQLite | 20,000 | 2,126 ms | Not measured successfully | 3,479 |

Replay is a diagnostic for event reading and state reconstruction; it does not
include public concept-view construction. Allocation is cumulative calling-thread
allocation, not retained memory or a minimum heap requirement. Import cases
include committed warmups and grow the initial graph/history during measurement.

The initial campaign used a 90-second **whole-case** budget, including JVM
startup, fixture loading, warmup, measurement and validation. SQLite's 20,000-node
cases timed out while loading, so they yielded no operation latency. A follow-up
with a 180-second budget completed its replay case: loading alone took 90.9 seconds,
and the full case took 118.1 seconds. The SQLite 20,000-node import remains censored;
no latency has been inferred for it.

Evidence: [initial campaign](../evidence/benchmarks/baseline-10k-20k/report.md),
[extended SQLite case](../evidence/benchmarks/sqlite-20k-extended/report.md).

## Lookup and history controls

| Control | Result |
| --- | --- |
| In-memory URI lookup, 100 concepts | Median 708 ms; range 700–712 ms; 942 MiB allocated/call |
| In-memory URI lookup, 1,000 concepts | Case budget exhausted during measurement phase |
| URI lookup, 10,000 concepts, both stores | Case budget exhausted during measurement phase |
| URI lookup, 20,000 concepts, in-memory | Case budget exhausted during measurement phase |
| Replay, 10,000 concepts, ten additional upsert contributions | Median 261 ms versus 99 ms without the additional history; allocated 872 versus 305 MiB/call |

A measurement-phase timeout does not establish an exact latency or a
90-second lower bound on one call: setup and warmup consume the same budget,
and the case requests several calls. The fixed-size history experiment retains
10,000 concepts and the same tree while increasing the event count from 11 to 21.
It demonstrates a history-dependent cost separately from graph cardinality.

Evidence: [lookup controls](../evidence/benchmarks/lookup-controls/report.md),
[history control](../evidence/benchmarks/history-10/report.md).

## Interpretation and next profiling targets

The code provides two concrete hypotheses for these results:

1. [`state/concepts`](../src/ai/obney/ontology/core/state.clj) invokes relationship
   view generation and sorting separately for every concept.
   [`get-concept-by-uri`](../src/ai/obney/ontology/interface.clj) constructs that
   full concept collection before selecting one URI. This is the first profiling
   target for lookup, enumeration, search and export.
2. `state/read-state` reads and folds all tenant graph events on every operation.
   Contributions retain full graph snapshots. At fixed loading batch size, a
   larger graph also means more/larger historical snapshots to reconstruct.
   The independent history control supports the hypothesis that replay is a
   significant cost in its own right.

These are source-backed hypotheses, not a sampled CPU profile attributing exact
percentages. Profile these paths separately and rerun the same campaigns after
any change. No production code or ontology semantics were changed in this
benchmarking work.

## Reproduce and validate

```sh
# Initial campaign: nonzero exit is expected when cases exhaust their budgets.
python3 scripts/benchmark.py --sizes 10000,20000 --backends in-memory,sqlite \
  --operations projection-replay,lookup-uri,import-delta \
  --samples 3 --warmup 1 --timeout 90 --output target/benchmarks/repeat-baseline

# Extended SQLite replay measurement.
python3 scripts/benchmark.py --sizes 20000 --backends sqlite \
  --operations projection-replay --samples 3 --warmup 1 --timeout 180 \
  --output target/benchmarks/repeat-sqlite-20k
```

The harness passed three tests with 16 assertions covering statistics, warmup
and sample boundaries, invalid options and failed-result rejection. A small
end-to-end smoke run exercised all 12 workloads on both backends with result
validation: [harness checks](../evidence/benchmark-harness-tests.log),
[smoke results](../evidence/benchmarks/smoke-1/report.md).

See [methodology and workload controls](BENCHMARKS.md) for details and limitations.
Archived commands retain the original output/temp paths as execution provenance;
choose a new output path when repeating a campaign.
