# Ontology memory adapter · paired evaluation · November 25, 2026

Passage reranking plus a revised reader instruction raised the local judge's score from 2/4 to 3/4 on the original development questions and from 10/14 to 13/14 on a separate, prespecified question set. On that second set, three verdicts changed from incorrect to correct and none changed in the other direction. Both configurations correctly abstained on both unanswerable questions.

| Configuration | Development correct | Holdout correct | Annotated turns selected, development | Annotated turns selected, holdout |
| --- | ---: | ---: | ---: | ---: |
| Original ontology adapter | 2/4 | 10/14 | 4/7 | 13/19 |
| Revised reader instruction alone | 2/4 | — | 4/7 | — |
| Passage retrieval and chronological ordering alone | 2/4 | — | 6/7 | — |
| Both changes | 3/4 | 13/14 | 6/7 | 16/19 |

The revised adapter retrieves 80 candidate turns from the ontology. A local cross-encoder scores overlapping passages against the question. The adapter fits the highest-scoring passages from distinct turns into the original 1,200-token evidence budget and orders them by conversation date. The reader receives explicit instructions for superseded facts and for combining distinct items across sessions. The store, embeddings, local Gemma reader and judge, and their decoding settings are shared. On the holdout, scoring and packing passages took a median 1.46 seconds per question on the M2 Max. This measures one local run.

The development gain was the 5K update, where the revised answer used the later 25:50 time. The clothing count still missed the reference of three. Two annotated turns describe an apparently overlapping exchange of boots at the same store on the same date. We preserved the published reference. On the holdout, the three newly credited answers recovered a 21-day interval, two hobbies mentioned across conversations, and four weekly fitness-class days.

The holdout selection used a fixed hash ordering, with two questions per answerable category and two abstentions. The configuration was fixed before examining holdout scores. The holdout and development sets share no labeled supporting-session ID. A selected turn counts as evidence even if the passage omits part of that turn. Each saved context was reconstructed and checked against the budget.

The reported totals use the original pilot's judge. Inspection found that it credited both systems for abstaining on an answerable dinner-preference question and rejected a revised answer that named the reference character, Fissionator. These grades remain visible in the raw results. The small stratified sample and the grader's errors limit how precisely these scores predict a full run.

[Paired answers, passages and timings](results.json) · [Configuration and reproduction instructions](../../../bench/memory/README.md) · [Original pilot](../longmemeval-s-pilot/report.md)
