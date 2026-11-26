"""Verify sources, budgets and paired answers on the evidence experiment."""

import argparse
from collections import Counter
from functools import cache
from pathlib import Path

from audit_followup import EVALUATOR_SHA, judge_answer, load_prompt_builder
from compare import collect_split, read
from pilot import DATA_SHA, READER, save

from evidence import use_evidence


def summarize(cases):
    methods = list(cases[0]["results"])
    if all("adaptive_route" in c for c in cases):
        methods.append("ontology-adaptive")
    summary = {}
    for method in methods:
        rows = [
            c["results"][
                c["adaptive_route"] if method == "ontology-adaptive" else method
            ]
            for c in cases
        ]
        summary[method] = {
            "questions": len(rows),
            "task_rubric_correct": sum(r["task_rubric_correct"] for r in rows),
            "pilot_judge_correct": sum(r["correct"] for r in rows),
            "selector_calls": sum(bool(sum(r.get("selector_usage", []))) for r in rows),
            "selector_tokens": sum(sum(r.get("selector_usage", [])) for r in rows),
            "by_category": {
                kind: {
                    "correct": sum(
                        r["task_rubric_correct"]
                        for c, r in zip(cases, rows, strict=True)
                        if c["type"] == kind
                    ),
                    "questions": sum(c["type"] == kind for c in cases),
                }
                for kind in sorted({c["type"] for c in cases})
            },
        }
    return summary


def audit(directory, evaluator, development=False, preferences=False):
    ids = (
        {"caf03d32", "a3838d2b", "1de5cff2", "8752c811", "32260d93"}
        if development
        else None
    )
    baseline = "ontology-quotes"
    cases = collect_split(
        directory,
        [baseline, "ontology-evidence"],
        len(ids) if ids else 5 if preferences else 21,
        ids,
    )
    if preferences and any(c["type"] != "single-session-preference" for c in cases):
        raise ValueError("Unexpected category in preference panel")
    if not (development or preferences) and (
        len({c["type"] for c in cases}) != 7
        or set(Counter(c["type"] for c in cases).values()) != {3}
    ):
        raise ValueError("Unexpected holdout strata")
    template = load_prompt_builder(evaluator)
    judge = cache(judge_answer)
    for case in cases:
        if (
            case["results"][baseline]["ranking"]
            != case["results"]["ontology-evidence"]["ranking"]
        ):
            raise ValueError(f"Different ontology candidates for {case['id']}")
        for result in case["results"].values():
            result["task_rubric_correct"] = judge(
                template(
                    result["category"],
                    case["question"],
                    case["reference"],
                    result["answer"],
                    abstention=result["abstention"],
                )
            )
        if not development:
            route = (
                "ontology-evidence"
                if use_evidence(case["question"])
                else "ontology-quotes"
            )
            case["adaptive_route"] = route
    return {
        "dataset_sha256": DATA_SHA,
        "rubric_sha256": EVALUATOR_SHA,
        "models": {
            m["name"]: m["digest"]
            for m in read(directory / "models.json")["models"]
            if m["name"] in (READER, "all-minilm:latest")
        },
        "summary": summarize(cases),
        "cases": cases,
    }


if __name__ == "__main__":
    parser = argparse.ArgumentParser()
    parser.add_argument("run", type=Path)
    parser.add_argument("evaluator", type=Path)
    parser.add_argument("output", type=Path)
    modes = parser.add_mutually_exclusive_group()
    modes.add_argument("--development", action="store_true")
    modes.add_argument("--preferences", action="store_true")
    args = parser.parse_args()
    save(
        args.output, audit(args.run, args.evaluator, args.development, args.preferences)
    )
