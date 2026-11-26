# LongMemEval-S with conversation context

Both runs use DeepSeek V4.1 Flash 1017 at low reasoning effort with a 1,024-token output limit. GPT-6 Luna grades complete answers with the same upstream rubric. Supermemory’s published Sonnet answers use the same saved judge verdicts as the baseline.

| Panel | Questions | Previous DeepSeek | Revised DeepSeek | Gained | Lost | Supermemory |
|---|---:|---:|---:|---:|---:|---:|
| All questions | 500 | 409 | 434 | 39 | 14 | 466 |
| Development | 93 | 81 | 83 | 6 | 4 | 85 |
| Remaining questions | 407 | 328 | 351 | 33 | 10 | 381 |
| Previously designated unseen subset | 394 | 318 | 339 | 31 | 10 | 368 |
| knowledge-update | 78 | 70 | 73 | 3 | 0 | 74 |
| multi-session | 133 | 101 | 109 | 13 | 5 | 117 |
| single-session-assistant | 56 | 46 | 46 | 1 | 1 | 56 |
| single-session-preference | 30 | 22 | 20 | 4 | 6 | 26 |
| single-session-user | 70 | 66 | 69 | 3 | 0 | 67 |
| temporal-reasoning | 133 | 104 | 117 | 15 | 2 | 126 |

The revised reader interleaves graph hits and reranked source turns, restores user turns from the highest-ranked sessions, and reads dated excerpts in conversation order. It uses up to 8,192 evidence tokens. The previous reader used up to 1,200 after a separate selection call. Context selection and the reader instruction changed together, so their individual contributions have not been measured.

The experiment issued 485 new reader requests. It carried forward the original empty-evidence answers for 15 operationally unavailable histories. Exact matches to earlier answers reuse their verdicts. All 500 cases count. Development used the 93 previously exercised IDs. The remaining 407 were evaluated with the frozen implementation. The 394-question subset was designated unseen before the earlier full benchmark and has since contributed to reported results.

On-demand reader charges were $0.5250 and judging cost $0.0108, totaling $0.5358. This experiment reused the retrieval artifacts and competitor judgments. The revised reader requires no separate evidence-selection inference call.

[Per-question evidence and scores](results.json) · [Previous full comparison](../longmemeval-s-full/report.md)
