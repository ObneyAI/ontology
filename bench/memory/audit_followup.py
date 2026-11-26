"""Check the paired contexts and grade answers with pinned upstream task prompts."""

import argparse
import hashlib
import importlib.util
from collections import Counter
from functools import cache
from pathlib import Path

import passages
from compare import collect_split, read
from pilot import BUDGET, DATA_SHA, READER, api, read_answer, save

EVALUATOR_SHA = "ecce9c4c79dc89d99534ac17b383a5cbb5b9f0c69ee98adaf0684742e3d95251"


def load_prompt_builder(path):
    if hashlib.sha256(path.read_bytes()).hexdigest() != EVALUATOR_SHA:
        raise ValueError("Unexpected upstream evaluator checksum")
    spec = importlib.util.spec_from_file_location("longmemeval_evaluator", path)
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module.get_anscheck_prompt


def judge_answer(prompt):
    response = api(
        "chat",
        model=READER,
        stream=False,
        think=False,
        messages=[{"role": "user", "content": prompt}],
        options={
            "temperature": 0,
            "seed": 42,
            "num_ctx": 8192,
            "num_predict": 10,
        },
    )
    verdict = response["message"]["content"].strip().lower().rstrip(".!")
    if verdict not in ("yes", "no"):
        raise ValueError(f"Unexpected rubric verdict: {verdict!r}")
    return verdict == "yes"


def audit(run, evaluator, mode="followup"):
    quote_test = mode in {"quotes", "regressions"}
    systems = (
        ["ontology-revised", "ontology-quotes"]
        if quote_test
        else [
            "ontology-revised",
            "ontology-anchored" if mode == "confirmation" else "ontology-followup",
            "ontology-grounded",
        ]
    )
    per_stratum = 3 if mode == "followup" else 2
    ids = {"1de5cff2", "8752c811"} if mode == "regressions" else None
    cases = collect_split(run, systems, len(ids) if ids else 7 * per_stratum, ids)
    if not ids and set(Counter(c["type"] for c in cases).values()) != {per_stratum}:
        raise ValueError("Unexpected stratum sizes")
    prompt_builder = load_prompt_builder(evaluator)

    judge = cache(judge_answer)

    for case in cases:
        baseline = case["results"]["ontology-revised"]
        if quote_test:
            quotes = case["results"]["ontology-quotes"]
            if (
                quotes["turns"] != baseline["turns"]
                or quotes["ranking"] != baseline["ranking"]
            ):
                raise ValueError("Quote repair changed retrieval")
            from quotes import repair

            inputs = read(run / case["id"] / "input.json")
            _, offsets, lengths, tokens = repair(
                inputs, baseline["window_offsets"], BUDGET
            )
            if (offsets, lengths, tokens) != (
                quotes["window_offsets"],
                quotes["window_lengths"],
                quotes["context_tokens"],
            ):
                raise ValueError(f"Quote repair did not reproduce for {case['id']}")
            case["changed_quote"] = (
                quotes["window_offsets"] != baseline["window_offsets"]
            )
        else:
            baseline, followup, grounded = (case["results"][name] for name in systems)
            initial_offsets = baseline["window_offsets"]
            if (
                grounded["window_offsets"] != initial_offsets
                or followup["planner_input_offsets"] != initial_offsets
                or followup["planner_context_tokens"] != baseline["context_tokens"]
            ):
                raise ValueError(f"Different first-pass evidence in {case['id']}")
            if (
                not followup.get("followup_hits")
                and followup["window_offsets"] != initial_offsets
            ):
                raise ValueError("A skipped follow-up changed the evidence")
        annotated = set(read(run / case["id"] / "gold.json")["annotated"])
        for row in case["results"].values():
            row["candidate_annotated"] = len(annotated.intersection(row["ranking"]))
            prompt = prompt_builder(
                row["category"],
                case["question"],
                case["reference"],
                row["answer"],
                abstention=row["abstention"],
            )
            # Identical responses to the same question must receive the same second grade.
            row["task_rubric_correct"] = judge(prompt)
        if (
            not quote_test
            and annotated
            and not baseline["abstention"]
            and not any(row["task_rubric_correct"] for row in case["results"].values())
        ):
            # Post-score diagnostic. Only this control gets benchmark annotations.
            inputs = read(run / case["id"] / "input.json")
            excerpt, keys, tokens, offsets = passages.context(
                inputs, sorted(annotated), BUDGET
            )
            answer, usage = read_answer(inputs, excerpt, "ontology-revised")
            verdict = judge(
                prompt_builder(
                    baseline["category"], case["question"], case["reference"], answer
                )
            )
            case["annotated_only_diagnostic"] = {
                "answer": answer,
                "task_rubric_correct": verdict,
                "window_offsets": offsets,
                "context_tokens": tokens,
                "selected_turns": keys,
                "available_annotated_turns": len(annotated),
                "reader_usage": usage,
            }
    return {
        "dataset_sha256": DATA_SHA,
        "upstream_evaluator_sha256": EVALUATOR_SHA,
        "models": {
            m["name"]: m["digest"]
            for m in read(run / "models.json")["models"]
            if m["name"] in (READER, "all-minilm:latest")
        },
        "rubric_judge_calls": judge.cache_info().misses,
        "cases": cases,
    }


if __name__ == "__main__":
    parser = argparse.ArgumentParser()
    parser.add_argument("run", type=Path)
    parser.add_argument("upstream_evaluator", type=Path)
    parser.add_argument("output", type=Path)
    modes = parser.add_mutually_exclusive_group()
    for mode in ["confirmation", "quotes", "regressions"]:
        modes.add_argument("--" + mode, dest="mode", action="store_const", const=mode)
    parser.set_defaults(mode="followup")
    args = parser.parse_args()
    save(args.output, audit(args.run, args.upstream_evaluator, args.mode))
