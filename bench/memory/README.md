# LongMemEval-S development pilot

Run from the repository root using Python 3.12, the Clojure CLI and Ollama. The run uses a local embedding model, reader and judge. The recorded run used Ollama 0.40.16 on an Apple M2 Max with 32 GiB RAM.

```sh
mkdir -p target/longmem
uv venv -p 3.12 target/longmem/venv
uv pip install --python target/longmem/venv/bin/python -r bench/memory/requirements.txt
curl -fL https://huggingface.co/datasets/xiaowu0162/longmemeval-cleaned/resolve/98d7416c24c778c2fee6e6f3006e7a073259d48f/longmemeval_s_cleaned.json -o target/longmem/dataset.json
```

Point the pilot at a running Ollama server. To use the default Ollama port, set the following variables in the shell running the benchmark.

```sh
export PILOT_OLLAMA=http://127.0.0.1:11434
export OLLAMA_HOST=$PILOT_OLLAMA
ollama pull all-minilm
ollama pull gemma4:26b
target/longmem/venv/bin/python bench/memory/pilot.py prepare target/longmem/pilot-1 --dataset target/longmem/dataset.json
target/longmem/venv/bin/python bench/memory/pilot.py run target/longmem/pilot-1
```

Use a new output directory for each campaign. Preparation verifies the dataset SHA-256 and writes one input file per question with all turns in timestamp order, compact source identifiers and shared MiniLM vectors. The selection rule takes the first answerable example in each of three categories (single-session user, multi-session and knowledge update) and the first abstention example in file order. Reserve these four IDs for development in future experiments. Each question gets fresh stores. Each turn keeps its speaker, text, timestamp and session. Gold answers, original session IDs and turn annotations live in scoring files. The query vector is used only at search time.

Each system returns up to 30 candidates. The common context builder takes candidates in order, truncates each turn to its first 240 cl100k tokens and skips passages that would overflow the 1,200-token context cap. The budget includes speaker, date and turn ID. The reader also receives the question and question date. Both reader and judge use Gemma 4 26B, temperature zero and seed 42. The judge sees the reference and abstention label. This is a local semantic grading prompt. Reader and judge token counts use the model's own tokenizer.

| System | Ingestion and retrieval |
| --- | --- |
| Ontology | Import one contribution per session through the public API, with a concept for each turn and a symmetric relationship between adjacent turns. Rank by lexical overlap and vector similarity, then repeat the search with one hop from the top three seeds. |
| Ontology without expansion | Use the first ranking from the same graph. This controls for extraction, indexing and embeddings. |
| Mem0 OSS | Store each turn with its source metadata using Memory.add with inference disabled. Search local Qdrant through Memory.search using the shared embedding. |
| Graphiti | Save each utterance as an EntityEdge linking its speaker to its session in embedded Kuzu. Query Graphiti's edge similarity search using the shared embedding. |
| No memory | Send the reader an empty evidence context. |

The comparison holds evidence units and embeddings fixed. Native LLM extraction, consolidation, and temporal invalidation require a separate experiment. Mem0 and Graphiti use vector retrieval in these configurations. The recorded per-question files contain the exact selected turn IDs and returned answer. Gold-session hits count distinct supporting sessions represented in the final context. Gold-turn hits count annotated turns selected into it, including truncated turns. The latter can overstate how much answer-bearing text actually reached the reader.

The run exits nonzero if any system fails and preserves the error in its result file. Inspect model digests in models.json when reproducing the run. Per-stage times and model token counts are diagnostic. Ontology graph construction and seed retrieval are shared between its two configurations. The baseline timings therefore do not add up to independent campaigns.

Validate all 20 result files, reconstruct their contexts and export the compact evidence bundle with:

```sh
target/longmem/venv/bin/python bench/memory/collect.py target/longmem/pilot-1 > target/longmem/results.json
```

## Passage reranking and dated answers

The opt-in `ontology-revised` configuration takes the first 80 expanded ontology search hits. A local MS MARCO MiniLM cross-encoder scores the question against overlapping 128-token passages from those turns. The context builder selects one passage per turn and presents the selected quotes in chronological order within the original 1,200-token cap. The reader uses dates to reconcile changed facts and checks for duplicates when combining evidence. The embedding model, reader model, decoding settings and judge match the original pilot.

Install the optional dependencies into the pilot environment and download the pinned ARM64 ONNX model used in the experiment:

```sh
uv pip install --python target/longmem/venv/bin/python -r bench/memory/requirements-reranker.txt
mkdir -p target/longmem/reranker
reranker_url=https://huggingface.co/cross-encoder/ms-marco-MiniLM-L-6-v2/resolve/233902d25c440f23af6f7d6e94d2946bac0bee0a
curl -fL "$reranker_url/onnx/model_qint8_arm64.onnx" -o target/longmem/reranker/model.onnx
curl -fL "$reranker_url/tokenizer.json" -o target/longmem/reranker/tokenizer.json
```

With Ollama running as described above, run the development controls and the paired evaluation. The development options change only the reader instruction, only passage selection and ordering, or both. The second split selects two distinct questions in each of the six answerable categories plus two abstentions by a fixed hash ordering. It excludes the original four questions and paired variants by ID.

```sh
pilot_python=target/longmem/venv/bin/python
export TOKENIZERS_PARALLELISM=false
$pilot_python bench/memory/pilot.py run target/longmem/pilot-1 --systems ontology ontology-reader ontology-passages ontology-revised
$pilot_python bench/memory/pilot.py prepare target/longmem/holdout-1 --dataset target/longmem/dataset.json --split holdout
$pilot_python bench/memory/pilot.py run target/longmem/holdout-1 --split holdout --systems ontology ontology-revised
$pilot_python bench/memory/compare.py target/longmem/pilot-1 target/longmem/holdout-1 > target/longmem/paired-results.json
```

The comparison verifies the context-token count from the stored turn IDs and passage offsets for each answer. The new per-answer `rerank_seconds` records passage scoring and packing, including initialization on the first call. A turn-level evidence hit credits a selected turn even if the answer-bearing span falls outside its selected window.

## One follow-up search and personalized advice

The `followup` split contains 21 questions chosen by fixed hash order, three per stratum. Selection excludes the earlier pilot and holdout questions, their paired abstention variants, and reused labeled supporting-session IDs. The label filter applies only during split selection. The model receives the question and its timestamped history.

The three configurations start with the same 80 ontology candidates, passage scores and 1,200-token context. The incumbent is `ontology-revised`. For `ontology-followup`, Gemma inspects the initial context and emits READY or one short query. If it emits a query, the adapter embeds it and searches the precomputed turn vectors for that one conversation history. It reranks 32 hits against the new query, reserves up to 600 tokens for original evidence, admits complementary passages up to 1,000 total tokens, then fills the remaining space with original evidence. A second pass can therefore expose more evidence to the *pipeline* while keeping the *answer reader* under the original cap. The planner output is never presented to the reader as evidence. This extra vector scan is implemented in the benchmark adapter.

The `ontology-grounded` configuration uses the incumbent passages and model settings. Its reader instruction allows new advice anchored in the user's recorded preferences, asks for direct factual answers and explicit counts, and preserves the requirement that recalled facts be supported by the conversation.

With the reranking dependencies and local Ollama server from the preceding section:

```sh
pilot_python=target/longmem/venv/bin/python
export TOKENIZERS_PARALLELISM=false
$pilot_python bench/memory/pilot.py prepare target/longmem/followup-1 --split followup --dataset target/longmem/dataset.json
$pilot_python bench/memory/pilot.py run target/longmem/followup-1 --split followup --systems ontology-revised ontology-followup ontology-grounded
uv pip install --python "$pilot_python" backoff==2.2.1 tqdm==4.67.1
curl -fL https://raw.githubusercontent.com/xiaowu0162/LongMemEval/9e0b455f4ef0e2ab8f2e582289761153549043fc/src/evaluation/evaluate_qa.py -o target/longmem/upstream_evaluate_qa.py
$pilot_python bench/memory/audit_followup.py target/longmem/followup-1 target/longmem/upstream_evaluate_qa.py target/longmem/followup-results.json
```

The audit validates evidence windows and budgets in all three configurations. It loads the pinned upstream evaluator after checking its checksum and applies its category-specific prompts to the saved answers using the same local Gemma model. Equivalent question/reference/answer prompts share a cached verdict. The per-answer results preserve both grader outputs.

A second planner, `ontology-anchored`, returns a schema-constrained search decision, a quoted anchor and a query. The adapter admits a follow-up search only if the anchor appears in both the question or initial evidence and the proposed query. It records any rejected plan and uses the incumbent context in that case. The confirmation split reserves two further questions per stratum and excludes the questions and labeled sources used in all three earlier sets.

```sh
$pilot_python bench/memory/pilot.py prepare target/longmem/confirmation-1 --split confirmation --dataset target/longmem/dataset.json
$pilot_python bench/memory/pilot.py run target/longmem/confirmation-1 --split confirmation --systems ontology-revised ontology-anchored ontology-grounded
$pilot_python bench/memory/audit_followup.py target/longmem/confirmation-1 target/longmem/upstream_evaluate_qa.py target/longmem/confirmation-results.json --confirmation
```

For an answerable question missed by all three configurations under the task-specific rubric, the audit also runs the incumbent reader on passages selected solely from annotated turns, if annotations exist. It retains that diagnostic answer separately. This is an offline check of evidence selection, using the same passage size and reader budget.

## Quoting numbered and bulleted lists

`ontology-quotes` keeps the incumbent turn ranking, selected source IDs, reader instructions and model. For a quote starting inside a list item, the adapter can shift the window back to the nearby item label. If a question asks for a numeric ordinal in a list and the source contains a unique matching item, it instead starts the quote at that item. It caps the replacement at the original quote's token length, checks the total evidence limit and records the actual offset and length. It makes no planner call.

Two diagnosed list questions from the 21-question experiment are regression cases. A separate stratified set of 14 questions excludes base IDs, repeated questions and labeled supporting-session IDs from all four earlier sets.

```sh
$pilot_python bench/memory/pilot.py prepare target/longmem/quotes-1 --split quotes --dataset target/longmem/dataset.json
$pilot_python bench/memory/pilot.py run target/longmem/quotes-1 --split quotes --systems ontology-revised ontology-quotes
$pilot_python bench/memory/audit_followup.py target/longmem/quotes-1 target/longmem/upstream_evaluate_qa.py target/longmem/quote-results.json --quotes
```

To check the two regressions against the saved baseline, run `ontology-quotes` on the `followup` split and use the audit's `--regressions` flag. Its output contains only the two development examples used to diagnose clipping.

## Source-linked events and preference evidence

`ontology-evidence` builds temporary records for the current question from the same 80 ontology retrieval candidates as `ontology-quotes`. The selector can also see the preceding user statement for a highly ranked assistant reply in the same session. It restores whole short turns and sentence or list boundaries in longer turns, and reserves part of its input for user testimony. A 4,200-token source pool goes to a schema-constrained selector. It groups numbered excerpts as events, facts, preferences, constraints or assistant statements. The adapter rehydrates the chosen text, speaker, report date and source IDs from the original history. Each stored source span uses character offsets into the turn. For explicit day or week offsets, the reader also gets the calendar date relative to the report. The reader treats groups as provisional.

The reader receives up to 1,200 evidence tokens. For advice it proposes actions consistent with reported experiences; for counts and comparisons it reconciles distinct people, events and dates. For missing factual evidence it can abstain. The selector and answer reader use the same pinned local Gemma model, with separate calls. The stored graph remains the turn-level graph; the typed records are assembled at query time. `ontology-adaptive` selects this path for explicit advice, aggregates and temporal comparisons and uses the quote-repair path otherwise. Routing uses only the question. The paired audit computes its result by selecting the output of the arm that would execute.

The `evidence` split reserves three new questions per category, including abstentions, after the quote validation split. The `preferences` split reserves five additional recommendation questions using a separate hash seed. Both exclude previously used questions, paired variants and labeled source IDs. Run with the same local server and reranker setup described above.

```sh
for split in evidence preferences; do
  $pilot_python bench/memory/pilot.py prepare target/longmem/$split-1 --split $split --dataset target/longmem/dataset.json
  $pilot_python bench/memory/pilot.py run target/longmem/$split-1 --split $split --systems ontology-quotes ontology-evidence
done
$pilot_python bench/memory/audit_evidence.py target/longmem/evidence-1 target/longmem/upstream_evaluate_qa.py target/longmem/evidence-results.json
$pilot_python bench/memory/audit_evidence.py target/longmem/preferences-1 target/longmem/upstream_evaluate_qa.py target/longmem/preference-results.json --preferences
```

The audit reconstructs the selector archive and the final records from the source spans, checks source scope, duplicates and both token budgets, and grades each answer with the pinned upstream task prompts. To run only the routed adapter, pass `--systems ontology-adaptive`.

For the reader-only control, `ontology-task-reader` uses the same question routing rule and gives the specialized reader the existing repaired quotes. It makes one answer call per question. The replay audit reproduces those quotes from the published offsets, checks the exact token count, runs and grades the specialized reader where the question selects it, and reuses the identical baseline answer elsewhere. Run it after each paired audit.

```sh
$pilot_python bench/memory/audit_reader.py target/longmem/evidence-1 target/longmem/evidence-results.json target/longmem/upstream_evaluate_qa.py target/longmem/evidence-reader.json
$pilot_python bench/memory/audit_reader.py target/longmem/preferences-1 target/longmem/preference-results.json target/longmem/upstream_evaluate_qa.py target/longmem/preference-reader.json
```

### Replay the saved evidence through Sonnet 5.5

The hosted replay takes the paired 21-question audit as its baseline. It reconstructs the selected quotations and reuses the local reader’s system instruction, question, date and evidence. OpenRouter receives only those reader messages. Set `OPENROUTER_API_KEY` in the environment and point `PILOT_OLLAMA` at the pinned local judge. The replay caches hosted completions by question and request hash so that grading can be repeated locally.

```sh
$pilot_python bench/memory/replay_sonnet.py target/longmem/evidence-1 target/longmem/evidence-results.json target/longmem/upstream_evaluate_qa.py target/longmem/sonnet-cache target/longmem/sonnet-results.json
```

Sonnet runs at low reasoning effort with 1,024 total generation tokens. The local task judge scores the first 160 visible cl100k tokens and also grades the complete response when the cutoff changes it. Gemma’s original 160-token generation limit uses its native tokenizer. The output records both texts, their grades, billed usage and the baseline and evaluator checksums.

### Full 500-question campaign

Use the same dataset, models, reranker, pinned evaluator and OpenRouter environment as the evidence and Sonnet runs. The primary score is the complete Sonnet answer. The secondary score uses the first 160 visible cl100k tokens. The preparation manifest also lists questions excluded from the unseen subset because of a prior run, paired ID or duplicate question. The competitor comparison downloads Supermemory's public Sonnet answers and grades both systems with the pinned local Gemma judge.

The following stages checkpoint per question. Their inputs are published by atomic rename, so the stages can overlap or run sequentially. The preparation step uses existing inputs from the named earlier campaign directories when present. Point `full-v1` at a new run directory for a separate campaign. The pinned split-selection rules reconstruct earlier question IDs even if their prepared inputs are absent. Keep the 21-question Sonnet cache to reuse those requests.

```sh
root=target/longmem
run=$root/full-v1
artifact=evidence/benchmarks/longmemeval-s-evidence
python=$root/venv/bin/python
$python bench/memory/full_prepare.py "$root" "$run"
clojure -J-Xmx2g -Sdeps '{:paths ["src" "bench"]}' -M -m ai.obney.ontology.memory-full "$run"
for stage in pool select answer grade; do
  $python bench/memory/full_run.py "$stage" "$run" "$artifact"
done
$python bench/memory/full_competitor.py "$run"
$python bench/memory/full_run.py competitor "$run" "$artifact"
$python bench/memory/full_report.py "$run" "$artifact" evidence/benchmarks/longmemeval-s-full/results.json
```

### Low-cost hosted reader comparison

The November 25 full comparison uses on-demand OpenRouter calls and reuses completed batch results. GPT-6 Luna selects source records and grades the answers. GPT-6 Luna, DeepSeek V4.1 Flash 1017 and Claude Sonnet 5.5 each answer from the same rendered records with low reasoning effort and a 1,024-token total limit. The budget comparison uses actual reported inference charges. The same evaluator separately grades Supermemory's published Sonnet answers with the same judge, question, reference and rubric.

The campaign retained completed retrieval pools when local model inference was stopped. For the remaining histories the hosted preparation step uses the MiniLM L6 embedding API and the reranking step uses the hosted NVIDIA reranker. The graph still produces the 80 initial candidates. Run hosted retrieval against a campaign directory with the pinned manifest. This procedure skips completed stages and can resume using saved per-request checkpoints.

```sh
$python bench/memory/hosted_retrieval.py prepare "$run"
clojure -J-Xmx768m -J-XX:ActiveProcessorCount=1 -Sdeps '{:paths ["src" "bench"]}' -M -m ai.obney.ontology.memory-full "$run"
$python bench/memory/hosted_retrieval.py rerank "$run"
$python bench/memory/full_competitor.py "$run"
$python bench/memory/frontier_run.py --on-demand "$run" "$root/frontier-v1" "$artifact"
$python bench/memory/frontier_report.py "$run" "$root/frontier-v1" "$artifact" evidence/benchmarks/longmemeval-s-full/results.json
```

The results bundle identifies each question's retrieval configuration, any rejected retrieval, the exposure split, reader outputs, judge verdicts, model names, price snapshot and inference receipts. Inputs for selection and answering contain no reference answers. For a question whose reranker rejects the request, no rejected content enters the selector or reader.

### DeepSeek reader with conversation context

This reader uses the same saved graph candidates and reranked source spans as the full comparison. It restores nearby user turns from highly ranked sessions and gives the reader up to 8,192 tokens of dated conversation. The on-demand run uses the existing full comparison for paired scores and preserves its unavailable-history cases. Rebuild both panels and the audited report from the project root.

```sh
baseline=evidence/benchmarks/longmemeval-s-full/results.json
for panel in development validation; do
  "$python" bench/memory/session_reader.py "$run" "$baseline" "$artifact" "$root/session-reader" "$panel"
done
"$python" bench/memory/session_report.py "$run" "$root/session-reader" evidence/benchmarks/longmemeval-s-session-reader
```

### Ontology retrieval correctness and evidence coverage

Run `clojure -M:test` for the library's behavioral contracts. To check the saved ontology search outputs before reranking, selection or answer generation, run:

```sh
"$python" bench/memory/audit_ontology.py "$run" evidence/benchmarks/longmemeval-s-full/ontology-retrieval.json
```

The audit validates source IDs, uniqueness, result limits and the expanded top-30 prefix for every history. It measures both any and complete annotated-turn coverage on answerable questions and lists missing turns for each incomplete top-80 result. Compare flat and expanded retrieval at the same cutoff to measure the contribution from following links between conversation turns. [Raw retrieval audit](../../evidence/benchmarks/longmemeval-s-full/ontology-retrieval.json)


To verify the complete misses through the public ontology API, reimport their histories, fetch each annotated concept by URI, and compare one-hop traversal with the adjacent source turns. This structural replay uses saved text and does not require model inference.

```sh
clojure -J-XX:ActiveProcessorCount=2 -J-Xmx768m -Sdeps '{:paths ["src" "bench"]}' -M \
  -m ai.obney.ontology.memory-miss-audit "$run" evidence/benchmarks/longmemeval-s-full/ontology-retrieval.json
```

### Verify session-diverse graph retrieval

New calls to the memory adapter record the seed policy and seed IDs alongside their ranked candidates. To compare against a frozen original campaign, replay the additional graph query from its saved flat ranking and original 80 candidates. All traversable edges are inside the selected sessions. The second command also reimports every history with retained embeddings and compares the normal adapter output with the replay, including candidate order.

```sh
rankings=target/ontology-session-seed-rankings.jsonl
clojure -J-Xmx768m -J-XX:ActiveProcessorCount=2 -Sdeps '{:paths ["src" "bench"]}' -M -m ai.obney.ontology.memory-seed-replay "$run" "$rankings"
clojure -J-Xmx768m -J-XX:ActiveProcessorCount=2 -Sdeps '{:paths ["src" "bench"]}' -M -m ai.obney.ontology.memory-seed-replay "$run" "$rankings" verify-retained
python3 bench/memory/verify_session_seeds.py "$run" "$rankings" evidence/benchmarks/longmemeval-s-full/ontology-session-seeds.json
```

### Verify the BM25 supplement

The current conversation adapter asks the ontology for BM25-ranked concepts. It considers the first 16 user statements in that ranking and adds up to eight missing statements. Each must outrank the lower-half assistant reply it replaces in the BM25 search. The graph-search prefix stays fixed, and BM25 document statistics cover every concept in the history. Run the two policies on the same original checkpoint to compare them before grading answers.

```sh
bm25=target/ontology-session-bm25.jsonl
clojure -J-Xmx768m -J-XX:ActiveProcessorCount=2 -Sdeps '{:paths ["src" "bench"]}' -M -m ai.obney.ontology.memory-seed-replay "$run" "$bm25" bm25
python3 bench/memory/verify_session_seeds.py "$run" "$bm25" evidence/benchmarks/longmemeval-s-full/ontology-bm25.json --baseline-replay "$rankings"
clojure -J-Xmx768m -J-XX:ActiveProcessorCount=2 -Sdeps '{:paths ["src" "bench"]}' -M -m ai.obney.ontology.memory-seed-replay "$run" "$bm25" bm25-verify-retained
```

### Verify explicit event-date matching

The default conversation adapter resolves explicit day and week offsets against each message's own timestamp. Retrieval uses the source words and dates only. The replay can reuse the verified BM25 candidate order for histories whose questions contain no supported offset and reruns the active histories through native graph and lexical search.

```sh
dated=target/ontology-date-events.jsonl
clojure -J-Xmx768m -J-XX:ActiveProcessorCount=2 -Sdeps '{:paths ["src" "bench"]}' -M -m ai.obney.ontology.memory-seed-replay "$run" "$dated" date "$bm25"
python3 bench/memory/verify_session_seeds.py "$run" "$dated" evidence/benchmarks/longmemeval-s-full/ontology-date.json --baseline-replay "$bm25"
clojure -J-Xmx768m -J-XX:ActiveProcessorCount=2 -Sdeps '{:paths ["src" "bench"]}' -M -m ai.obney.ontology.memory-seed-replay "$run" "$dated" date-verify-retained
```

### Verify aggregate-question retrieval

The default memory adapter restores opening user statements from sessions already represented in the strongest hits for a count, age, amount or average question. The checkpoint replay uses the dated candidate ranking and the original turn sequence. Fresh verification runs the normal adapter from source inputs for every history with retained embeddings.

```sh
aggregate=target/ontology-aggregate.jsonl
clojure -J-Xmx768m -J-XX:ActiveProcessorCount=2 -Sdeps '{:paths ["src" "bench"]}' -M -m ai.obney.ontology.memory-seed-replay "$run" "$aggregate" aggregate "$dated"
python3 bench/memory/verify_session_seeds.py "$run" "$aggregate" evidence/benchmarks/longmemeval-s-full/ontology-aggregate.json --baseline-replay "$dated"
clojure -J-Xmx768m -J-XX:ActiveProcessorCount=2 -Sdeps '{:paths ["src" "bench"]}' -M -m ai.obney.ontology.memory-seed-replay "$run" "$aggregate" aggregate-verify-retained
```

### Compare the routed reader with Supermemory

Use the aggregate rankings and frozen retrieval checkpoints above. The control reuses identical reader inputs from the earlier DeepSeek run. The routed reader sends synthesis, advice and empty-answer retries to Haiku on demand. Each panel writes resumable request receipts. The report reconstructs each conversation excerpt from its original turn and checks its hash.

```sh
archive=evidence/benchmarks/longmemeval-s-evidence
prior=evidence/benchmarks/longmemeval-s-session-reader/results.json
control=target/longmem/parity-current
routed=target/longmem/parity-wide-haiku
"$python" bench/memory/parity_run.py "$run" "$aggregate" "$archive" "$prior" "$control" all
for panel in dev remaining; do
  "$python" bench/memory/parity_run.py "$run" "$aggregate" "$archive" "$control/all.json" "$routed" "$panel" --mode wide --model anthropic/claude-haiku-5.5
done
"$python" bench/memory/parity_report.py "$run" "$control/all.json" "$routed" evidence/benchmarks/longmemeval-s-parity
```

Pass the development alternative's result with `--pilot` to include its cost. [Paired results](../../evidence/benchmarks/longmemeval-s-parity/report.md)

To reproduce the subsequent source-review experiment, use the routed reader results as the draft answers. These commands make additional on-demand calls. The second stage reuses the first judge verdict whenever it selects the original answer.

```sh
dest=evidence/benchmarks/longmemeval-s-parity
reviews=target/longmem/parity-review
decisions=target/longmem/parity-decide
for panel in dev remaining; do
  "$python" bench/memory/parity_review.py "$run" "$aggregate" "$dest/results.json" "$reviews" "$panel"
  "$python" bench/memory/parity_decide.py "$run" "$dest/results.json" "$reviews" "$decisions" "$panel"
done
"$python" bench/memory/parity_review_report.py "$run" "$dest/results.json" "$reviews" "$decisions" "$dest"
```
