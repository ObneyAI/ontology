# Source review on LongMemEval-S

Each proposed revision is grounded in a second search through sessions reached by the ontology. Claude Haiku 5.5 selects relevant turns in small groups. GPT-6.1 Terra receives the selected turns and the first answer. When Terra proposes a change, Haiku chooses between the two answers after reading both sets of evidence. Candidate order depends only on the question text. Reference answers enter only during scoring.

| Panel | Questions | Before review | Reviewed | Supermemory | Only ours correct | Only Supermemory correct |
|---|---:|---:|---:|---:|---:|---:|
| All questions | 500 | 451 | 446 | 466 | 21 | 41 |
| Development | 93 | 90 | 91 | 85 | 7 | 1 |
| Remaining | 407 | 361 | 355 | 381 | 14 | 40 |
| Previously designated unseen | 394 | 349 | 344 | 368 | 14 | 38 |
| knowledge-update | 78 | 72 | 72 | 74 | 3 | 5 |
| multi-session | 133 | 112 | 111 | 117 | 6 | 12 |
| single-session-assistant | 56 | 47 | 47 | 56 | 0 | 9 |
| single-session-preference | 30 | 25 | 26 | 26 | 4 | 4 |
| single-session-user | 70 | 69 | 68 | 67 | 3 | 2 |
| temporal-reasoning | 133 | 126 | 122 | 126 | 5 | 9 |
| With usable evidence | 485 | 449 | 444 | 452 | 20 | 28 |
| Unavailable histories | 15 | 2 | 2 | 14 | 1 | 13 |

On the 93 development questions, accepting the reviewer’s revisions directly scored 89. Rechecking changes using both sets of evidence scored 91. The latter configuration was frozen before the remaining 407. All 15 histories with unavailable evidence retain their original answers and count in every full score. Both systems use the same saved GPT-6 Luna verdicts for unchanged answers.

Of the missed answerable questions with usable evidence, the reader inputs included excerpts of every annotated turn in 37 cases, some in 1, and none in 1. A turn can be included as an excerpt, so this check does not establish that the reader saw its entire text.

This review spent $2.3257 on demand. Total incremental charges for the control, reader comparison and review experiments were $3.8693. These totals exclude previously paid ingestion, retrieval and comparison runs.

[Review decisions and changed answers](review.json) · [Original routed answers, comparison and source spans](report.md) · [Supermemory’s published runs](https://supermemory.ai/research/runs/longmemeval/)
