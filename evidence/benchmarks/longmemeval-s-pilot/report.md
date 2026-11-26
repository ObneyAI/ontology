# LongMemEval-S development pilot · 2026-11-25

On four questions, the ontology answered the degree question correctly and abstained on a question about a hamster. It missed the clothing count and the updated running time. Turning off graph expansion gave the same four verdicts. Mem0 OSS and Graphiti also gave the same verdicts with shared turn-level inputs and embeddings.

| Configuration | Answerable questions correct | Correct abstentions | Annotated turns selected | Supporting sessions reached on answerable questions |
| --- | ---: | ---: | ---: | ---: |
| Ontology, adjacent-turn expansion | 1/3 | 1/1 | 4/7 | 5/6 |
| Ontology, expansion disabled | 1/3 | 1/1 | 4/7 | 6/6 |
| Mem0 OSS, inference disabled | 1/3 | 1/1 | 4/7 | 6/6 |
| Graphiti, edge vector search | 1/3 | 1/1 | 4/7 | 6/6 |
| Empty context | 0/3 | 1/1 | 0/7 | 0/6 |

The pilot processed all 1,970 conversation turns across 189 sessions in the four histories. All 20 question/configuration runs completed. Reconstructing each reader context from saved candidate IDs reproduced its selected turns and token count. The recorded answers agree with the binary grades on inspection. This is a four-question development sample scored with a local reader and judge. It measures retrieval and answering when extraction is held fixed. It cannot establish a product ranking or predict full-dataset accuracy.

**Multi-session synthesis.** The reference count was three clothing items. Each retrieval configuration put just one of three annotated turns into the reader context. Expansion retrieved passages from two of the three supporting sessions. The other three configurations reached all three sessions but still missed the count. Session hits alone hid the evidence gap.

**Knowledge update.** The ontology context contained the user's May 23 personal best of 27:12 and the May 30 report of 25:50. The reader answered 27:12 under all four retrieval configurations. In this case retrieving the newer evidence succeeded, while choosing the current value failed.

**Single-session recall and abstention.** Every retrieval configuration recovered the Business Administration degree. All five configurations abstained on the unsupported hamster name. Empty context also produced abstentions on the three answerable questions.

The raw bundle records each answer, ranking, selected evidence, model digests, stage timings and token usage. The runner documents the selection rule, input boundaries, local model configuration and context budget. [Results](results.json) · [Reproduction instructions](../../../bench/memory/README.md) · [Pinned dataset](https://huggingface.co/datasets/xiaowu0162/longmemeval-cleaned/tree/98d7416c24c778c2fee6e6f3006e7a073259d48f)
