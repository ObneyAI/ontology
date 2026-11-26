# Full LongMemEval-S hosted reader comparison

Each of the three readers received the same evidence for each question. GPT-6 Luna selected source records and graded complete answers against the pinned upstream rubric. We also graded the first 160 visible cl100k tokens of our answers. The comparison uses Supermemory’s published Claude Sonnet 5.5 answers, regraded by the same Luna judge on the same 500 questions.

| Reader | Full answers / 500 | First 160 tokens / 500 | Unseen / 394 | Ours only / Supermemory only | Reader charges |
|---|---:|---:|---:|---:|---:|
| GPT-6 Luna | 399 | 399 | 310 | 15 / 82 | $0.0387 |
| DeepSeek V4.1 Flash 1017 | 409 | 402 | 318 | 15 / 72 | $0.0933 |
| Claude Sonnet 5.5 | 418 | 404 | 326 | 14 / 62 | $1.5246 |
| Supermemory · Sonnet 5.5 | 466 | — | 368 | — | Vendor charges unavailable |

The unseen set excludes the 93 previously run question IDs and another 13 paired or text-equivalent questions. It also has no labeled supporting-session overlap with those earlier runs.

## Where scores differ

| Question type | Questions | Luna | DeepSeek | Sonnet | Supermemory |
|---|---:|---:|---:|---:|
| knowledge-update | 78 | 67 | 70 | 70 | 74 |
| multi-session | 133 | 98 | 101 | 107 | 117 |
| single-session-assistant | 56 | 45 | 46 | 47 | 56 |
| single-session-preference | 30 | 25 | 22 | 24 | 26 |
| single-session-user | 70 | 67 | 66 | 65 | 67 |
| temporal-reasoning | 133 | 97 | 104 | 105 | 126 |

Of 81 missed Sonnet questions with annotated supporting turns that called for a substantive answer, 24 had no annotated turn in the evidence. The other 57 included at least one annotated turn. An excerpt can still omit a needed fact, date or event.

## Retrieval and operational limits

| Retrieval condition | Questions | Luna | DeepSeek | Sonnet | Supermemory |
|---|---:|---:|---:|---:|
| ms-marco-MiniLM-L-6-v2 | 431 | 347 | 356 | 364 | 399 |
| nvidia/nemotron-3.5-rerank-8b:free | 69 | 52 | 53 | 54 | 67 |
| Provider-declined subset | 13 | 0 | 0 | 2 | 13 |

We preserved the 431 retrieval pools already assembled by the original MiniLM reranker. The remaining 69 used hosted NVIDIA reranking after local model inference stopped. Thirteen requests were declined and their readers received empty evidence. All 500 questions count toward the headline scores. Seven histories contained blank messages, which were skipped during graph ingestion. The response budget was 1,024 tokens including reasoning. Supermemory used its own retrieval and generation settings. These are paired system comparisons, and all three of our reader arms share these retrieval conditions.

Evidence selection was unavailable for 2 additional questions. Each reader received the question with empty evidence for those cases.

## Cost

The completed comparison used $1.9612 in receipted inference charges. This includes $0.2383 for shared evidence selection, $0.0450 for grading our answers and $0.0213 for grading the competitor. Successful hosted retrieval stages reported $0.0230. The Sonnet arm reused 20 identical earlier calls originally billed at $0.0627 in total. The reader column shows incremental charges. Superseded batch jobs have reported $0.1958 so far, with 6 still pending at the accounting snapshot. The earlier interrupted reader run incurred $0.4148 in separate charges. Local electricity and the competitor’s original inference are excluded.

[Per-question answers, price snapshot and inference receipts](results.json) · [Run instructions](../../../bench/memory/README.md) · [Published Supermemory run](https://supermemory.ai/research/runs/longmemeval/) · [OpenRouter batch pricing](https://openrouter.ai/blog/announcements/batch-api/)
