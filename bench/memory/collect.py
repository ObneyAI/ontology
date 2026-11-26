"""Validate a complete pilot and emit a compact JSON evidence bundle to stdout."""

import json
import sys
from pathlib import Path

from pilot import BUDGET, DATA_SHA, SYSTEMS, context


def read(path):
    return json.loads(path.read_text())


def collect(run):
    folders = sorted(p.parent for p in run.glob("*/input.json"))
    if len(folders) != 4:
        raise ValueError("Expected four question directories")
    cases = []
    for folder in folders:
        case = read(folder / "input.json")
        gold = read(folder / "gold.json")
        if any(
            set(t) != {"id", "date", "session", "role", "text", "vector"}
            for t in case["turns"]
        ):
            raise ValueError("Unexpected ingestion fields")
        rows = []
        for system in SYSTEMS:
            row = read(folder / (system + "-result.json"))
            if (
                "error" in row
                or row["question_id"] != folder.name
                or row["system"] != system
            ):
                raise ValueError(f"Incomplete result for {folder.name} {system}")
            _, turns, tokens = context(case, row["ranking"])
            if (
                tokens > BUDGET
                or tokens != row["context_tokens"]
                or turns != row["turns"]
            ):
                raise ValueError("Context did not reproduce")
            if system != "no-memory" and len(set(row["ranking"])) != 30:
                raise ValueError("Expected 30 distinct retrieval candidates")
            rows.append(row)
        cases.append(
            {
                "id": folder.name,
                "question": case["question"],
                "reference": gold["reference"],
                "turns": len(case["turns"]),
                "sessions": len(gold["sources"]),
                "gold_turns": gold["annotated"],
                "embedding": read(folder / "embedding.json"),
                "results": rows,
            }
        )
    return {
        "dataset_sha256": DATA_SHA,
        "models": {
            m["name"]: m["digest"]
            for m in read(run / "models.json")["models"]
            if m["name"] in ("all-minilm:latest", "gemma4:26b")
        },
        "cases": cases,
    }


if __name__ == "__main__":
    print(json.dumps(collect(Path(sys.argv[1])), indent=2))
