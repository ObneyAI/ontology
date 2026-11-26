# Using remembered facts in LongMemEval-S answers

The new adapter scored **19/21** on a fresh balanced LongMemEval-S panel, up from **15/21** with repaired quotes. On five additional recommendation questions it scored **5/5**, up from **0/5**. Both results use the task-specific rubric.

| Adapter | Balanced score | Preference score | Extra selector calls on balanced / preference panels |
|---|---:|---:|---:|
| Baseline repaired quotes | 15/21 | 0/5 | 0 / 0 |
| Task-specific reader on repaired quotes | 17/21 | 3/5 | 0 / 0 |
| Evidence selection on routed questions | 18/21 | 3/5 | 12 / 3 |
| Evidence selection on every question | 19/21 | 5/5 | 21 / 5 |

On 21 balanced questions, always selecting evidence consumed 111,272 extra model tokens. Routing consumed 63,933. Switching only the reader instructions recovered two of the four net gains at zero selector calls.

## What changed

The incumbent retrieves 80 ontology candidates, reranks passages and gives the reader up to 1,200 tokens of dated quotes. The new evidence adapter starts with the same ranked candidates. It restores surrounding statements, including the user's preceding message for highly ranked assistant replies, then groups exact source spans into events, preferences, constraints, facts and assistant statements. The final reader can distinguish advice from factual recall and use the date of a statement to interpret explicit relative dates. These records are created per question in the benchmark adapter.

We tested the full adapter and a cheaper route that invokes the selector only for questions matching advice, aggregation or temporal cues. Both policies were fixed before the final paired run. During that run we added a reader-only control to test how much of the gain came from instructions. It uses the same routing rule and baseline quotes. The routed score reuses the matching answer from the two evaluated branches. The reader-only control regenerates routed answers on the exact baseline context and reuses the identical baseline response elsewhere.

## Interpreting the comparisons

The balanced panel includes three questions in each of seven strata, including abstentions. A separate panel tests five more preference questions. Both panels exclude earlier question IDs, equivalent question texts and labeled supporting sessions. The five development cases were chosen because of previously diagnosed failures. Each comparison pairs the same history and question. The judge uses pinned upstream task prompts on local Gemma 4 26B. The artifacts also include the pilot's generic grades for the two main arms.

On the balanced panel, full evidence selection recovered the three preference answers, a Sunday shift assignment and the duration of sculpting classes. It lost one previously correct reading-event interval, producing five gains and one loss. The generic grader scored both arms 17/21. All three explicit abstention cases passed in both arms.

Temporal reasoning remained at 1/3. The router labeled all three “how many days/weeks” questions as aggregation. On the car suspension question the full reader extracted both event dates and calculated 38 days in its explanation, yet led with 158 days. On the reading-event question it cited dates 18 days apart but abstained because it could not confirm the title of the book. These are distinct failures in calculation consistency and identifying a referenced event.

On the five preference questions, the full adapter earned credit on all five. It connected a successful lemon poppyseed cake with a new request about baking for colleagues, and a previous Denver concert with a new trip. The question-only routing rule missed indirect requests for travel tips and painting inspiration. The generic grader awarded 4/5 to both the baseline and the full adapter. Local rubric credit also leaves room for weak answers. For example, the Tokyo answer still suggested buying an IC card even though the reference says the user already owns one. Four of the five full-adapter preference answers reached the 160-token generation limit.

On the five development cases, the baseline scored 2/5 and the full adapter 3/5. Both answered the two list questions correctly. The gain was a stand-up recommendation tailored to the user's interest in storytelling. The slow-cooker advice missed the reference preference. For charity events, the baseline counted two and the full adapter counted three against a reference of four.

The selector has a 4,200-token source allowance in addition to the 1,200-token final reading limit. The audit reproduces each source span from the original history, checks both budgets, and verifies identical initial candidate rankings. Reported selector usage includes input and output with Gemma's tokenizer. The small panels and a shared local reader and judge limit generalization to the full dataset.

[Balanced paired answers](heldout.json) · [Balanced reader control](heldout-reader.json) · [Preference paired answers](preferences.json) · [Preference reader control](preferences-reader.json) · [Development answers](development.json) · [Run instructions](../../../bench/memory/README.md)

## Sonnet 5.5 on the same 21 questions

With the same saved evidence and local task judge, Sonnet scored **18/21** when graded on the first 160 visible cl100k tokens. Its complete responses scored **20/21**. Gemma’s measured baseline was **19/21** at a native 160-token generation limit.

| Reader and scoring condition | Overall | Date reasoning | Preference | Correct abstentions |
|---|---:|---:|---:|---:|
| Gemma 4 26B, original generation limit | 19/21 | 1/3 | 3/3 | 3/3 |
| Sonnet 5.5, first 160 visible tokens | 18/21 | 1/3 | 3/3 | 3/3 |
| Sonnet 5.5, complete answers | 20/21 | 3/3 | 3/3 | 3/3 |

Eight Sonnet answers exceeded the visible cap. Only the book-reading interval (18 days) and car-suspension interval (38 days) changed verdict when the judge could see the full response. Sonnet identified the two dates and discussed uncertainty before giving the elapsed time. It lost one question Gemma had answered correctly. Asked how many projects the user led, it abstained because the records described planning projects and leading a team without explicitly linking leadership to each project. The reference count was two.

The hosted request reused the original evidence spans, task mode, serialized question and date, and reader instructions. Sonnet used low reasoning effort with a 1,024-token generation allowance including reasoning. The visible cutoff approximates Gemma’s output budget with a different tokenizer. The full-response comparison changes both reader and output allowance. Both scoring conditions use the same 21 hosted responses. OpenRouter reported 13,292 input tokens, 4,666 completion tokens and **$0.073244** total for the reader calls. Selection and grading ran locally. [Paired answers, grades and usage](heldout-sonnet.json)
