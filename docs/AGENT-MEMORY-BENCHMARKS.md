# Agent-memory products and evaluation plan

Research date: 2026-10-03. This extends the [product comparison research](BENCHMARK-PRODUCTS.md)
with agent-memory systems. Capabilities are documented by the linked primary
sources; priorities and experiments are our recommendations. No provider has been
installed, provisioned or benchmarked during this research.

For agent-memory positioning, prioritize **Mem0, Graphiti, Hindsight, Zep Cloud,
and Supermemory**. Add **Letta** to evaluate agents that actively manage memory.
**Cognee** is a close graph-construction comparison, and **LangMem** is a useful
composable baseline. Treat hosted and self-hosted editions as separate entrants.

Use **LongMemEval-S** for the first common evaluation and **MemoryArena** for
whether memory improves subsequent actions. Add **LoCoMo** for comparison with
published vendor evaluations. Database performance remains a complementary track.

## Completed LongMemEval-S comparison

Our routed DeepSeek and Claude Haiku 5.5 reader scored **451/500 (90.2%)**, 15 answers behind Supermemory’s **466/500 (93.2%)** under the same judge. Both scored 126/133 on temporal reasoning. All 15 histories without usable evidence count in the comparison. [Paired answer scores and source spans](../evidence/benchmarks/longmemeval-s-parity/report.md)

On the 485 histories with usable evidence, the routed reader scored 449 to Supermemory’s 452. On the other 15, the scores were 2 to 14. A second reader that checked proposed revisions against both sets of source turns scored 446/500 overall, so the routed reader remains the best measured configuration. [Source-review experiment](../evidence/benchmarks/longmemeval-s-parity/review.md)

Our previous full run scored **434/500 (86.8%)** with DeepSeek V4.1 Flash and dated conversation context. This version gained 25 correct answers over our previous DeepSeek run, including 13 on temporal reasoning and eight on multi-session questions. The same judge scored Supermemory’s published answers at 466/500. [Paired scores and inference costs](../evidence/benchmarks/longmemeval-s-session-reader/report.md)

On all 500 questions, our Claude Sonnet 5.5 reader answered 418 correctly (83.6%). Supermemory’s published answers with the same reader scored 466 (93.2%) under the same GPT-6 Luna judge. Our DeepSeek V4.1 Flash reader scored 409 (81.8%). The largest category difference was temporal reasoning, where our Sonnet run scored 105/133 and Supermemory scored 126/133. [Full results, costs and retrieval conditions](../evidence/benchmarks/longmemeval-s-full/report.md)

## Product shortlist

| Product | Documented design | Why compare it with us; proposed experiment |
| --- | --- | --- |
| **Mem0** | User/session/agent memory with OSS and managed options. Its current README describes accumulating extracted facts, entity linking, and semantic/keyword/entity retrieval. Headline results explicitly concern the managed platform and include proprietary optimizations. [Implementation and edition distinction](https://github.com/mem0ai/mem0) | A direct reference for adding memory to an existing agent. Measure preference retention, changing facts, temporal answers, ingestion cost and retrieval quality. Label SDK and Platform runs separately; do not assign Platform scores to an OSS run. |
| **Graphiti** | Open-source temporal knowledge graph construction, with structured/unstructured inputs and hybrid retrieval. [Overview](https://help.getzep.com/graphiti/getting-started/overview) | A close architectural comparison for entities, relationships, provenance and evolving knowledge. Compare entity collisions, multi-hop recall, superseded facts and temporal questions. Include extraction, embedding and backing-database costs. |
| **Zep Cloud** | Managed context retrieval over user/shared graphs. Its documentation describes asynchronous ingestion, graph scoping and context assembly; the old Community Edition is deprecated. [FAQ](https://help.getzep.com/faq) | A product-level benchmark for useful context per token, readiness after writes and retrieval latency. Run separately from Graphiti: a Graphiti measurement does not reproduce the hosted service's architecture or reported performance. |
| **Hindsight** | Retain/recall/reflect operations; memories include world facts, experiences and consolidated observations with evidence. Retrieval combines semantic, keyword, graph and temporal signals. [Repository and architecture](https://github.com/vectorize-io/hindsight) | Strong fit for experience consolidation and evidence-backed memory. Compare conflicting evidence, temporal retrieval and useful lessons from prior tasks. Use `recall` with a common answer model in the retrieval track; evaluate `reflect` as an additional reasoning stage and count its model work. |
| **Supermemory** | Memory, retrieval and entity profiles exposed through an API, with graphs organized around entity identifiers. [Product model](https://supermemory.ai/docs/overview/what-is-supermemory) | Compare profile accuracy, preference changes, cross-session context and latency at a fixed context budget. A profile endpoint and a search endpoint are different configurations and must be identified in results. |
| **Letta** | Stateful agents with editable in-context memory blocks, persisted messages and memory tools. [Stateful-agent model](https://docs.letta.com/v1-sdk/concepts/stateful-agents) | Tests whether agents can decide what to retain and retrieve. Compare task success and unnecessary memory operations under the same model and tool-call budget. Pin the runtime/API version: the linked stateful-agent reference is in the V1 SDK documentation. |
| **Cognee** | Builds persistent memory from data using knowledge graphs and retrieval. [Project](https://github.com/topoteretes/cognee), [pipeline overview](https://docs.cognee.ai/reference/notebooks_collabs) | Compare document/conversation-to-graph quality, source-grounded retrieval and entity resolution. Separate graph construction from raw retrieval and generated answers. |
| **LangMem** | LLM-driven memory extraction/consolidation, with semantic, episodic and procedural memory patterns. [Conceptual guide](https://github.com/langchain-ai/langmem/blob/main/docs/docs/concepts/conceptual_guide.md) | A configurable baseline for extraction and learning from experience. Hold its model, storage and prompts fixed. It can also inform a shared extraction layer for our ontology, but that would be a new integration rather than existing functionality. |

The practical first self-hosted group is **our ontology + memory adapter, Mem0
OSS, Graphiti, and Hindsight**. The hosted group adds **Mem0 Platform, Zep, and
Supermemory**. Letta belongs in the agent-controlled memory experiment, where its
read/write decisions are part of the treatment rather than hidden harness work.

## Benchmarks to use

| Benchmark | What the primary source establishes | Recommended role |
| --- | --- | --- |
| **LongMemEval v1 / S** | 500 questions with six task labels; covers updates, temporal and multi-session reasoning, preferences and abstention. Evidence annotations support retrieval evaluation. [Official repository](https://github.com/xiaowu0162/LongMemEval) | First shared benchmark. Report overall and per-category answer accuracy, abstention and evidence retrieval separately. Pin original versus cleaned data, split and exact revision. S and M are different history scales. |
| **LoCoMo** | Long-term conversational memory dataset, evaluation code and conversation-derived observations. [Official repository](https://github.com/snap-research/locomo) | Secondary conversational evaluation and bridge to vendor literature. State the exact question IDs, categories, exclusions and grader; the name alone does not define a comparable protocol. Ingest conversation content, not supplied answer/evidence annotations. |
| **MemoryAgentBench** | Incremental multi-turn evaluation of accurate retrieval, test-time learning, long-range understanding and conflict resolution. Includes integrations for several memory systems. [Official repository](https://github.com/HUST-AI-HYZ/MemoryAgentBench) | Diagnose which memory behavior succeeds or fails after incremental ingestion. Pin dependency versions and inspect adapters before reuse; the project itself notes integration dependency issues. |
| **MemoryArena** | Interdependent multi-session tasks where prior actions and feedback must support later actions; includes web navigation, planning, search and formal reasoning. [Official project](https://memoryarena.github.io/) | The primary behavioral test for an agentic-memory claim. Measure completed tasks, later-session improvement, repeated mistakes, tool calls, tokens and elapsed time under a fixed agent configuration. |
| **LongMemEval-V2** | A May 2026 work-in-progress benchmark of environment experience: state recall/tracking, workflows, gotchas and premise awareness, using compact evidence gathered from trajectories. [Paper](https://arxiv.org/abs/2605.12493) | Follow-up for workflow/site memory. It evaluates a different setting from conversational LongMemEval v1; do not combine their scores. Verify released artifacts and evaluation stability before committing a campaign. |

The [Letta memory leaderboard](https://www.letta.com/blog/letta-leaderboard/)
also provides reading/writing/updating tasks, but primarily compares language
models inside Letta. It is useful for choosing controlled agent behavior, not
an independent ranking of memory vendors.

## Published results are leads, not our leaderboard

Three current primary-source examples show why we need our own common run:

- Mem0 reports **94.4 on LongMemEval** for its managed platform, with a top-200
  retrieval budget and about 6.8k returned tokens. These are vendor-reported
  results, not measurements of our selected OSS configuration. [Source](https://github.com/mem0ai/mem0)
- Zep reports **90.2% LongMemEval accuracy** for its documented multi-scope
  configuration, with a specified reader/judge and retrieval recipe. It also
  reports a different score for single-call auto search. [Source](https://www.getzep.com/research/)
- Supermemory labels its headline **97% LongMemEval-S result as Recall@20 with
  aggregation**. Preserve that metric definition; it is not automatically the
  same measure as either answer-accuracy figure above. [Source](https://supermemory.ai/research/)

These observations do not establish an ordering. Differences in models, context
budgets, aggregation, input data and edition prevent that inference. We have not
reproduced these results. Retrieval latency also excludes different amounts of
extraction, indexing, agent reasoning and answer generation across reports.

## What our ontology needs for this comparison

The [public API](../src/ai/obney/ontology/interface.clj) provides graph mutation,
source/contribution evidence, entity resolution, search and revision exports.
The [README](../README.md) assigns extraction and embedding generation to the
application. A conversation benchmark therefore needs a documented adapter:

1. Ingest timestamped messages or episodes with stable source identifiers.
2. Extract entities and factual statements, preserving speaker and source spans;
   distinguish user statements, tool observations and agent assertions.
3. Import concepts/relationships with explicit identity and resolution policies.
4. Retrieve relevant evidence and assemble a bounded context for a common reader.
5. Expose memory tools to a fixed agent for the action-based experiment.

**Revision history is not automatically valid-time memory.** A snapshot records
the graph at a committed revision; answering what was true in the world on a date
also requires handling effective dates, corrections and late-arriving evidence.
Similarly, storage-level optimistic conflicts differ from contradictory facts.
Define these policies and measure them rather than inferring them from event
sourcing. Imports retain omitted knowledge, so omission is not a forgetting API.
Any suppression/deletion behavior added by an adapter must be identified as such.

The older ORC architecture report covers additional learning functionality, but
it does not establish that the standalone checkout implements that behavior.

## Common protocol and controls

Run two distinct experiments:

- **End-to-end memory:** each system receives the same timestamped source history
  and uses its documented ingestion pipeline. Use a common answer model, prompt
  and judge wherever the API permits. Internal model choices remain a recorded
  difference for hosted services. Measure ingestion, readiness, retrieval,
  returned context and answering separately.
- **Controlled ontology contribution:** hold extraction output, embeddings,
  reader and context budget fixed where adapters support it. Compare graph-aware
  retrieval with lexical/vector retrieval over the same evidence. This isolates
  our graph's contribution; do not present it as a native-product leaderboard.

Include no-memory, full-history (where it fits), running-summary, BM25/vector
RAG, and files-plus-search baselines. Disable graph expansion and entity resolution
in separate ontology ablations. Improvement over a weak no-memory baseline alone
does not establish that the ontology adds value.

Start with a disjoint development subset for adapter debugging, then freeze
configuration before scoring the full declared set. Do not select prompts on
test answers. Strip reference answers, `has_answer`, evidence labels and similar
annotations before ingestion; use them only for scoring. Ingest in temporal order,
wait for declared readiness, isolate histories, and prevent evaluation answers
from entering later questions' memory unless the benchmark explicitly requires it.

Measure answer accuracy and abstention separately from evidence recall. Define
whether retrieval success means any supporting evidence or all evidence needed
for a multi-hop answer. Preserve provenance IDs through extraction where possible;
otherwise mark exact evidence recall unmeasurable rather than inventing matches.
Report both question-weighted and category-averaged accuracy. Failures and timeouts
stay in the declared denominator; repeated runs and paired uncertainty estimates
are more useful than tiny differences in one aggregate score.

Compare at fixed returned-context token budgets, with a separately labeled native
default run. Equal top-k values are insufficient because a result may be a short
fact, a whole session or a synthesized summary. Count model calls, ingestion and
consolidation tokens, reranking, retrieval, answer generation, infrastructure and
provider charges. Report judge cost separately. For agents, cap tools, tokens and
elapsed time and include any background consolidation work.

Add a small ontology-specific diagnostic suite: two users with conflicting
preferences, same-name entities, corrections with historical queries, late facts,
unsupported claims, source attribution, exact replay and restart. Test forgetting
and authorization boundaries when exposed, recording unsupported functionality.
This suite complements public benchmarks rather than being folded into their
published scores.

## Existing harnesses and first implementation

The [Redis agent-memory benchmark](https://github.com/redis/agent-memory-server/blob/main/agent-memory-benchmark/README.md)
is a promising starting point: it standardizes LongMemEval ingestion, shared
answering and judging, retains per-question artifacts, and lists adapters for
Mem0, LangMem, Zep, Graphiti and Supermemory, among others. It documents a
`MemoryStore` extension interface. It is vendor-maintained and has not been
executed or audited here; inspect its adapters and defaults against the protocol
above before adopting it. Its default task-averaged score must be distinguished
from a question-weighted score.

[Supermemory's memorybench](https://github.com/supermemoryai/memorybench) is another
candidate with ingestion/search/answer/evaluation stages and multiple providers.
The [upstream LongMemEval evaluator](https://github.com/xiaowu0162/LongMemEval)
should remain the reference for task labels and scoring conventions.

Recommended implementation order: a shared ingestion/context adapter for our
ontology; a small correctness run against Mem0 OSS and Graphiti; a frozen full
LongMemEval-S campaign; Hindsight and hosted-product runs under the same outer
protocol; then MemoryArena with a fixed agent and memory-tool budget. LoCoMo is
the next conversational cross-check. No claim of superior memory quality should
precede those measurements.

The first controlled four-question development run completed on November 25, 2026. It covers the ontology with and without graph expansion, Mem0 OSS, Graphiti and an empty-context control. [Pilot results](../evidence/benchmarks/longmemeval-s-pilot/report.md)

A follow-up evaluated passage reranking and dated evidence on a separate stratified sample. The local judge scored the original ontology adapter at 10/14 and the revised adapter at 13/14. [Paired evaluation and grading caveats](../evidence/benchmarks/longmemeval-s-rerank/report.md)

Subsequent experiments evaluated two follow-up search planners and a list-quotation repair. The planners tied the incumbent. Quote repair recovered two diagnosed failures and tied the incumbent on 14 fresh questions. [Experiments and per-question results](../evidence/benchmarks/longmemeval-s-followup/report.md)

A source-linked evidence adapter improved the local task-rubric score from 15/21 to 19/21 on a fresh balanced panel, and from 0/5 to 5/5 on a separate preference panel. It uses an extra selector call. Both methods scored 1/3 on date comparisons and tied on the generic grader. The report also evaluates cheaper routing and reader-only alternatives. [Evidence selection results and costs](../evidence/benchmarks/longmemeval-s-evidence/report.md)
