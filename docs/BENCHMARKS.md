# Ontology benchmarks

The target is 10,000+ concepts. Benchmarks exercise the standalone library and
real Grain stores without ORC or an external model. They measure current behavior;
there is no assumed latency target or automatic performance pass/fail threshold.
No production implementation is changed to improve the numbers.

## Run a bounded campaign

```sh
python3 scripts/benchmark.py \
  --sizes 10000,20000 --backends in-memory,sqlite \
  --operations projection-replay,lookup-uri,import-delta \
  --samples 5 --warmup 2 --timeout 180 \
  --output target/benchmarks/my-baseline
```

Each backend/size/operation runs sequentially in a separate JVM with a 2 GiB
maximum heap (`--heap` overrides it). The default batch size is 1,000 concepts.
A case's time limit includes JVM startup, fixture construction, warmup, measured
samples and result validation. A timeout is **not** a measured operation latency
or an exact lower bound on it. The campaign records its last phase and any
completed setup/measurement rows. It continues to later cases and exits nonzero
if any case failed or timed out. Partial results remain reviewable.

The output directory must be new so previous runs are retained. Temporary
synthetic databases are removed after each case, including terminated cases.
The launcher uses Python 3 and a POSIX process group to stop only its own JVM.

## Workloads

| Operation | Measured work |
| --- | --- |
| `projection-replay` | Diagnostic: read tenant events and reconstruct graph state; does not construct the public concept/relationship views |
| `lookup-uri` | Public qualified lookup of the last generated URI |
| `list-concepts` | Public graph-scoped enumeration |
| `search-lexical` | Lexical ranking with a ten-result limit |
| `search-graph` | Tree traversal from the root through depth two |
| `search-vector` | Ranking using supplied 32-dimensional synthetic embeddings; no network/model inference |
| `export-current` | Current graph materialization, RDF serialization, count and checksum |
| `export-first-import` | Historical snapshot at revision two (the first batch, not the entire final graph) |
| `retry-contribution` | Identical committed request; result/revision remain unchanged |
| `update-concept` | Update one concept's attributes and commit |
| `import-resolved` | Import up to ten aliases matching existing labels with explicit resolution enabled |
| `import-delta` | Add ten new concepts with valid hierarchy links per invocation |

All results are checked outside the timed interval; anomalies and wrong
cardinalities/revisions abort the case instead of yielding deceptively fast
numbers. Returned API values are eagerly realized by the implementation.
Fixture generation/loading is separately measured, including graph creation,
request construction and initial contribution commits.
Store opening and SQLite schema initialization occur before that setup timer;
they are included in the whole-case time budget.

The fixture is a deterministic binary tree: N concepts, N−1 stored hierarchy
edges and their derived inverse views. Labels, descriptions and 32-dimensional
vectors are reproducible. There is one tenant and one ontology per case; loading
uses additive contributions with stable synthetic IDs. It is a synthetic sparse
graph, not a representative sample of an unknown production dataset. It does not
exercise high-degree graphs, multiple tenants, parallel clients, TBox/ABox-heavy
imports, full OWL reasoning or remote storage.

Write warmups also commit. Measured writes therefore run against growing history,
and delta imports grow the graph by ten concepts each time. Start/end revisions
are recorded; the displayed concept count identifies the initial fixture.
Request construction is included in write timings. Successful read/retry cases
verify that revision did not change.

## Separate graph size from event history

Extra historical contributions upsert an existing concept without increasing the
concept count. Compare otherwise identical campaigns, for example:

```sh
python3 scripts/benchmark.py --sizes 10000 --backends in-memory \
  --operations projection-replay --history 0 --output target/benchmarks/history-0
python3 scripts/benchmark.py --sizes 10000 --backends in-memory \
  --operations projection-replay --history 25 --output target/benchmarks/history-25
```

`--batch-size` also changes initial event history and loading cost. Keep it fixed
when comparing graph sizes. A snapshot-bearing contribution is not equivalent in
size or replay cost to a small concept-update event.

## Artifacts and interpretation

The campaign writes `manifest.json` and a Markdown summary. Every case records:

- `metadata.edn`: configuration, source/dependency hashes, JVM arguments/version,
  OS/architecture, available processors and maximum heap.
- `results.edn`: all completed raw samples, monotonic elapsed time, per-sample GC
  deltas and calling-thread allocated bytes where supported by the JVM.
- `results.csv`: aggregate medians, minimum/maximum, GC activity, revisions and
  SQLite database/WAL/SHM file bytes, when applicable.
- `status.edn`: normal completion or a caught failure. A killed JVM may not write
  this file; campaign status is authoritative for timeouts.
- A log identifying setup versus the operation being measured.

Allocation is bytes allocated by the calling JVM thread, **not** retained heap,
peak memory, process RSS or total allocations across worker threads. GC counters
cover the JVM, and their measurement surrounds the timed call. No forced GC is
performed. SQLite file size includes its journal files and is not a measurement
of physical disk writes or fsync latency. OS filesystem caches are not flushed.

These are warmed single-client wall-clock samples, not concurrent throughput or
cold-start measurements. Five samples provide a preliminary median and range;
they do not justify p95/p99 claims or a statistically significant regression
threshold. Repeat campaigns on an idle machine before drawing close comparisons.
Large differences and timeouts identify paths that warrant profiling.

For a quick validation of every workload, without per-case process isolation:

```sh
clojure -M:bench --sizes 20 --samples 2 --warmup 1 --batch-size 10 --delta-size 2
```

The direct Clojure entry point has no timeout. Use the Python launcher for large
runs. It retains the raw data needed for later comparisons rather than treating
small test-suite execution times as ontology capacity measurements.

Check the harness's sample boundaries, statistics, option validation and anomaly
handling independently of performance measurements:

```sh
clojure -Sdeps '{:paths ["src" "bench"]}' -M bench/check_harness.clj
```

## Evidence kept in Git

Run benchmarks under ignored `target/`. For new archived campaigns, keep one
`report.md` summary and one consolidated `results.edn` containing the campaign
manifest and each case's metadata, raw results and status. Per-case logs, CSVs
and duplicate reports are local artifacts. Existing older baseline archives
retain their original layout.
