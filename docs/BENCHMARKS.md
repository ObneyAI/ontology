# Ontology benchmarks

The target is 10,000+ concepts. Benchmarks exercise the standalone library and
real Grain stores without ORC or an external model. They measure current behavior;
there is no assumed latency target or automatic performance pass/fail threshold.
No production implementation is changed to improve the numbers.

See [product comparison research](BENCHMARK-PRODUCTS.md) for external candidates,
selection priorities, and controls for comparable measurements.
The [agent-memory comparison plan](AGENT-MEMORY-BENCHMARKS.md) covers memory
products, public quality benchmarks, and the adapter needed around this library.

## Primary ontology checks

1. Run `clojure -M:test` to check the public API, graph behavior, persistence and replay. The November 25 run passed 138 tests and 1,235 assertions, including the aggregate-question retrieval regression test.
2. Measure retrieval before the reranker or reader. The primary quality measure is the number of answerable questions whose candidates contain **every annotated turn**, at a stated result limit. Also record any-hit coverage and the number of annotated turns recovered. Validate source IDs, cardinality, uniqueness and the ranking prefix on every history. Compare retrieval variants at the same limit.
3. Track downstream answer accuracy separately. It can change when the reader, prompt or evidence budget changes even when the ontology returns identical candidates.

### Raw LongMemEval-S retrieval

The importer represents each nonblank message as a concept, links adjacent messages in a session and supplies a stored embedding. The flat search ranks text and embedding matches. The original expanded search also traversed one hop from the first three flat hits. The audit checked all 500 rankings. Coverage uses the 470 answerable questions, containing 886 annotated question–turn pairs.

| Search | Any annotated turn | Every annotated turn | Annotated turns retrieved |
|---|---:|---:|---:|
| Flat, top 30 | 442/470 | 377/470 | 737/886 |
| Expanded, top 30 | 443/470 | 382/470 | 744/886 |
| Original expanded, top 80 | 462/470 | 438/470 | 840/886 |
| Session-diverse retrieval, top 80 | 463/470 | 441/470 | 845/886 |
| Session-diverse retrieval with BM25, top 80 | 464/470 | 447/470 | 852/886 |
| Retrieval with event-date matching, top 80 | 467/470 | 450/470 | 855/886 |
| Current retrieval with opening user statements for aggregate questions, top 80 | 467/470 | 455/470 | 864/886 |

Fourteen of the 16 missing multi-session turns in the date baseline belonged to sessions already present in the results. For questions about counts, ages, amounts or averages, the current adapter collects the sessions represented by the first 40 hits. In ranked session order, it restores up to eight missing opening user statements. It replaces lower-half assistant replies only when the session already has a retained user statement. This requires only turn order, roles and the previous ranking.

Rankings changed for 226 questions. Nine questions gained one annotated turn and none lost one at the 80-result cutoff. All five newly complete cases were multi-session questions, including counts of museum visits and magazine subscriptions. Of 470 answerable questions, 455 have complete evidence, 12 have partial evidence and three have none. Seven of the partial cases span multiple sessions. The change was informed by this benchmark's concentration of supporting facts in opening messages. This measures retrieval coverage on the same benchmark before answer generation. [Per-question verification](../evidence/benchmarks/longmemeval-s-full/ontology-aggregate.json)

### Event-date baseline

The date supplement acts on questions with one supported day or week offset. It computes the target calendar day from the question timestamp. It prioritizes user statements whose own timing language (such as “today” or “yesterday”) points near that target, then considers statements reported near it. Day offsets allow one day of tolerance and week offsets allow three. Within each group, distance and then BM25 rank determine the order. The supplement admits up to eight turns while preserving the first 40 candidates.

Ten of the 500 questions triggered the supplement and nine rankings changed. The questions about planting tomato saplings, signing a first client and buying sculpting tools went from zero to complete annotated evidence. No question lost an annotated turn at this cutoff. The earlier experiment that ranked both types of date clues together recovered only the sculpting-tools turn. The choice to prioritize explicit event dates was informed by these benchmark cases.

At this stage, 450 questions had complete evidence, 17 had partial evidence and three had none. Twelve of the partial cases span multiple sessions. Two complete misses concern preferences. The remaining temporal miss has conflicting dates. A March 20 question asks about five days earlier (March 15), while its annotated March 10 message says the activity happened “yesterday” (March 9). All cases retain their original annotations and count in coverage. [Per-question date verification](../evidence/benchmarks/longmemeval-s-full/ontology-date.json)

### BM25 baseline

The BM25 stage adds a second text search. BM25 weights terms by how often they occur in the question's history, how often they occur in a given turn, and the length of that turn. Among the top 16 user statements under BM25, up to eight missing statements can replace weaker lexical matches from assistant replies in the lower half of the session-diverse ranking. The first 40 positions remain in their original order.

Across the same 500 benchmark histories, seven questions gained an annotated turn and none lost one. Four temporal questions and two multi-session questions gained complete evidence. For example, the search recovered the user's dated report about meeting Emma for lunch. At this stage 447 questions had complete evidence, 17 had partial evidence and six had none. These measurements are for retrieval on this benchmark before answer generation. [Per-question BM25 verification](../evidence/benchmarks/longmemeval-s-full/ontology-bm25.json)

### Session-diverse baseline

The session-diverse stage takes the highest flat hit from each of up to eight distinct sessions. An ontology graph search follows up to four adjacent-turn links from those seeds. At the 80-result cutoff the adapter keeps the first 40 results and can replace up to 16 lower-ranked assistant replies whose preceding user message is already present. It uses those places for previously absent user turns, taking the closest first and breaking distance ties by seed rank.

Five questions gained one annotated turn each. No question lost an annotated turn at the top-80 cutoff. Three gained complete coverage, and the projects-led question went from zero to one of four annotated turns. Seven questions missed every annotated turn at this stage. All 500 histories used the native graph traversal. Fresh imports and searches on all 93 histories with retained embeddings reproduced the replayed candidate order exactly. For 407 histories the replay used the original cached first-stage rankings. These measurements precede reranking and answer generation. [Per-question verification](../evidence/benchmarks/longmemeval-s-full/ontology-session-seeds.json)

### Tracing the original eight complete misses

Reimporting all eight histories through the public ontology API recovered all 12 annotated turns by URI with exact source text and attributes. Graph-only searches from each known URI returned exactly the expected one-hop neighbors. These source and edge checks use the annotation only to select the diagnostic lookup. None has a top-three seed in a supporting session. The graph contains only within-session edges in this benchmark, so expansion from those seeds cannot reach the annotated turns at any depth. Seven searches still found a different turn in a supporting session through the other ranking signals. The ranks below are one-based. For the projects question the nearest distance is across its four annotated turns.

| Question ID | Topic | First top-80 hit in a supporting session | Nearest top-80 hit to an annotated turn in graph hops |
|---|---|---:|---:|
| 6d550036 | Projects led | 7 | 1 |
| 06f04340 | Homegrown ingredients | 11 | 1 |
| d6233ab6 | High school reunion | None | — |
| gpt4_468eb063 | Lunch with Emma | 7 | 4 |
| gpt4_1e4a8aec | Gardening two weeks earlier | 69 | 11 |
| eac54add | Business milestone | 68 | 4 |
| 6e984302 | Competition purchase | 24 | 1 |
| gpt4_68e94288 | Social media activity | 59 | 1 |

For the two cases with embeddings retained in their full-run inputs, an unrestricted replay of the same ontology search reproduced the original top 80 in order. The homegrown-ingredients turn ranked 89th. The four project turns ranked 160th, 162nd, 173rd and 270th. The other six full-run inputs retain text and candidate rankings but omit the embeddings needed for that exact replay.

An offline experiment kept the first 72 results, then filled eight positions with previously unselected one-hop neighbors in hit order, falling back to the original ranking when needed. Across all 500 histories, this recovered one complete miss and raised annotated-turn coverage from 840 to 845. Complete coverage stayed at 438/470: three questions gained full evidence and three lost it. The current session-diverse strategy above supersedes this earlier trial. [Machine-readable audit and case IDs](../evidence/benchmarks/longmemeval-s-full/ontology-retrieval.json) · [Reproduction instructions](../bench/memory/README.md#ontology-retrieval-correctness-and-evidence-coverage)

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
