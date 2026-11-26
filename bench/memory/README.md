# LongMemEval-S benchmark

Run commands from the repository root. The graph retrieves conversation turns for
500 questions. The retrieval audit measures coverage against the 470 answerable
questions before reranking or answer generation. The reader comparison grades
answers against the published Supermemory run using a common judge.
[Retrieval results](../../evidence/benchmarks/longmemeval-s-full/ontology-aggregate.json) · [Paired reader results](../../evidence/benchmarks/longmemeval-s-parity/results.json) · [Source-review results](../../evidence/benchmarks/longmemeval-s-parity/review.json)

## Campaign inputs

Use Python 3.12 and the Clojure CLI. The following paths match the saved campaign.
The dataset and evaluator are pinned so a replay uses the same questions and rubric.

```sh
root=target/longmem
run=$root/full-v1
python=$root/venv/bin/python
artifact=evidence/benchmarks/longmemeval-s-evidence
mkdir -p "$root"
test -x "$python" || uv venv -p 3.12 "$root/venv"
uv pip install --python "$python" -r bench/memory/requirements-reranker.txt backoff==2.2.1 tqdm==4.67.1
curl -fL https://huggingface.co/datasets/xiaowu0162/longmemeval-cleaned/resolve/98d7416c24c778c2fee6e6f3006e7a073259d48f/longmemeval_s_cleaned.json -o "$root/dataset.json"
curl -fL https://raw.githubusercontent.com/xiaowu0162/LongMemEval/9e0b455f4ef0e2ab8f2e582289761153549043fc/src/evaluation/evaluate_qa.py -o "$root/upstream_evaluate_qa.py"
```

For replay, reuse the frozen `full-v1` manifest and per-question checkpoints.
They live under `target/` and are local campaign inputs. A fresh checkout can
inspect the committed results immediately. To recreate the original manifest
and inputs from scratch, `full_prepare.py` checks the installed Ollama model
digests against [the pinned model manifest](../../evidence/benchmarks/longmemeval-s-evidence/heldout.json) and prepares missing histories. For this step, set `PILOT_OLLAMA` to the server with those models installed.

```sh
"$python" bench/memory/full_prepare.py "$root" "$run"
```

To resume a campaign with the pinned manifest, set `OPENROUTER_API_KEY` for
hosted embeddings, reranking, reading and judging. These stages reuse completed
checkpoints and can incur provider charges.

```sh
"$python" bench/memory/hosted_retrieval.py prepare "$run"
clojure -J-Xmx768m -J-XX:ActiveProcessorCount=1 -Sdeps '{:paths ["src" "bench"]}' -M -m ai.obney.ontology.memory-full "$run"
"$python" bench/memory/hosted_retrieval.py rerank "$run"
"$python" bench/memory/full_competitor.py "$run"
"$python" bench/memory/frontier_run.py --on-demand "$run" "$root/frontier-v1" "$artifact"
"$python" bench/memory/frontier_report.py "$run" "$root/frontier-v1" "$artifact" evidence/benchmarks/longmemeval-s-full/results.json
```

## Verify ontology retrieval

Audit the original top-30 and top-80 rankings, then replay each supplement against
its predecessor. Each verification checks the candidate order against the normal
adapter for histories with retained embeddings. These steps use saved inputs
and require no model calls.

```sh
"$python" bench/memory/audit_ontology.py "$run" evidence/benchmarks/longmemeval-s-full/ontology-retrieval.json
rankings=target/ontology-session-seed-rankings.jsonl
clojure -J-Xmx768m -J-XX:ActiveProcessorCount=2 -Sdeps '{:paths ["src" "bench"]}' -M -m ai.obney.ontology.memory-seed-replay "$run" "$rankings"
clojure -J-Xmx768m -J-XX:ActiveProcessorCount=2 -Sdeps '{:paths ["src" "bench"]}' -M -m ai.obney.ontology.memory-seed-replay "$run" "$rankings" verify-retained
"$python" bench/memory/verify_session_seeds.py "$run" "$rankings" evidence/benchmarks/longmemeval-s-full/ontology-session-seeds.json

bm25=target/ontology-session-bm25.jsonl
clojure -J-Xmx768m -J-XX:ActiveProcessorCount=2 -Sdeps '{:paths ["src" "bench"]}' -M -m ai.obney.ontology.memory-seed-replay "$run" "$bm25" bm25
"$python" bench/memory/verify_session_seeds.py "$run" "$bm25" evidence/benchmarks/longmemeval-s-full/ontology-bm25.json --baseline-replay "$rankings"
clojure -J-Xmx768m -J-XX:ActiveProcessorCount=2 -Sdeps '{:paths ["src" "bench"]}' -M -m ai.obney.ontology.memory-seed-replay "$run" "$bm25" bm25-verify-retained

dated=target/ontology-date-events.jsonl
clojure -J-Xmx768m -J-XX:ActiveProcessorCount=2 -Sdeps '{:paths ["src" "bench"]}' -M -m ai.obney.ontology.memory-seed-replay "$run" "$dated" date "$bm25"
"$python" bench/memory/verify_session_seeds.py "$run" "$dated" evidence/benchmarks/longmemeval-s-full/ontology-date.json --baseline-replay "$bm25"
clojure -J-Xmx768m -J-XX:ActiveProcessorCount=2 -Sdeps '{:paths ["src" "bench"]}' -M -m ai.obney.ontology.memory-seed-replay "$run" "$dated" date-verify-retained

aggregate=target/ontology-aggregate.jsonl
clojure -J-Xmx768m -J-XX:ActiveProcessorCount=2 -Sdeps '{:paths ["src" "bench"]}' -M -m ai.obney.ontology.memory-seed-replay "$run" "$aggregate" aggregate "$dated"
"$python" bench/memory/verify_session_seeds.py "$run" "$aggregate" evidence/benchmarks/longmemeval-s-full/ontology-aggregate.json --baseline-replay "$dated"
clojure -J-Xmx768m -J-XX:ActiveProcessorCount=2 -Sdeps '{:paths ["src" "bench"]}' -M -m ai.obney.ontology.memory-seed-replay "$run" "$aggregate" aggregate-verify-retained
```

## Compare the routed reader with Supermemory

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

Pass the development alternative's result with `--pilot` to include its cost.

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
