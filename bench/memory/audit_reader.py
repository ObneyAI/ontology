"""Replay the question-specific reader on exactly the audited baseline quotes."""

import argparse
from pathlib import Path

import tiktoken
from audit_followup import judge_answer, load_prompt_builder
from compare import quote_context, read
from pilot import BUDGET, read_answer, save

from evidence import requested_task


def evaluate(run, paired, evaluator):
    rubric = load_prompt_builder(evaluator)
    enc = tiktoken.get_encoding("cl100k_base")
    rows = []
    for sample in paired["cases"]:
        case = read(run / sample["id"] / "input.json")
        baseline = sample["results"]["ontology-quotes"]
        mode = requested_task(case["question"])
        row = {"id": sample["id"], "category": sample["type"], "reader_mode": mode}
        if mode:
            turns = {t["id"]: t for t in case["turns"]}
            prompt = quote_context(turns, baseline, enc)
            tokens = len(enc.encode_ordinary(prompt))
            if tokens != baseline["context_tokens"] or tokens > BUDGET:
                raise ValueError(f"Baseline quote mismatch for {sample['id']}")
            answer, usage = read_answer(case, prompt, "ontology-evidence", mode)
            correct = judge_answer(
                rubric(
                    baseline["category"],
                    case["question"],
                    sample["reference"],
                    answer,
                    abstention=baseline["abstention"],
                )
            )
            row.update(answer=answer, reader_usage=usage)
        else:
            correct = baseline["task_rubric_correct"]
        row["task_rubric_correct"] = correct
        rows.append(row)
    return {
        "task_rubric_correct": sum(r["task_rubric_correct"] for r in rows),
        "questions": len(rows),
        "cases": rows,
    }


if __name__ == "__main__":
    parser = argparse.ArgumentParser()
    for arg in ("run", "paired", "evaluator", "output"):
        parser.add_argument(arg, type=Path)
    args = parser.parse_args()
    save(args.output, evaluate(args.run, read(args.paired), args.evaluator))
