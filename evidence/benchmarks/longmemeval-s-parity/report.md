# LongMemEval-S answer comparison

Supermemory’s published Claude Sonnet 5.5 answers and our answers were graded with the same pinned upstream prompts and GPT-6 Luna judge. Each of the 500 questions contributes one point. [Supermemory run archive](https://supermemory.ai/research/runs/longmemeval/)

| Panel | Questions | DeepSeek control | Routed adapter | Supermemory | Only ours correct | Only Supermemory correct |
|---|---:|---:|---:|---:|---:|---:|
| All questions | 500 | 432 | 451 | 466 | 20 | 35 |
| Development | 93 | 82 | 90 | 85 | 6 | 1 |
| Remaining | 407 | 350 | 361 | 381 | 14 | 34 |
| Previously designated unseen | 394 | 338 | 349 | 368 | 14 | 33 |
| knowledge-update | 78 | 73 | 72 | 74 | 3 | 5 |
| multi-session | 133 | 108 | 112 | 117 | 6 | 11 |
| single-session-assistant | 56 | 46 | 47 | 56 | 0 | 9 |
| single-session-preference | 30 | 19 | 25 | 26 | 3 | 4 |
| single-session-user | 70 | 69 | 69 | 67 | 3 | 1 |
| temporal-reasoning | 133 | 117 | 126 | 126 | 5 | 5 |
| Unavailable histories | 15 | 2 | 2 | 14 | 1 | 13 |

The control uses the current ontology rankings and an 8,192-token conversation reader. The earlier version scored 434/500. The routed adapter uses the question wording to recognize synthesis and advice requests. It also retries empty answers from the control that exhausted their output budget. These routes use Claude Haiku 5.5 with up to 24,576 evidence tokens and 4,096 output tokens. It preserves the other DeepSeek V4.1 Flash answers. Context includes dated source excerpts from native ontology hits, the archived reranked passage pool and their linked sessions.

The adapter sent 318 questions to Haiku. All 15 unavailable histories retain the original empty-evidence answers. The 93 development questions guided model selection. We froze that choice before grading the remaining 407. The project has evaluated these benchmark questions before.

This campaign spent $1.5436 on demand, including $0.0836 to update the control and $0.1646 for the alternative development reader. The selected reader and its judge cost $1.2852 and $0.0102, respectively. These incremental charges reuse the existing retrieval, prior answers and competitor verdicts.

[Per-question answers and source spans](results.json) · [Earlier 434/500 run](../longmemeval-s-session-reader/report.md)
