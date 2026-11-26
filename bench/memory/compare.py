"""Collect paired ontology results and verify the exact evidence budget."""

import argparse
import json
from collections import Counter
from pathlib import Path

import tiktoken
from passages import WINDOW
from pilot import BUDGET, DATA_SHA, context


def read(path):
    return json.loads(path.read_text())


def quote_context(turns, row, enc):
    blocks = []
    for key, start in sorted(row["window_offsets"].items()):
        turn = turns[key]
        length = row.get("window_lengths", {}).get(key, WINDOW)
        snippet = enc.decode(enc.encode_ordinary(turn["text"])[start : start + length])
        blocks.append(f"[{key} {turn['date']} {turn['role']}] {snippet}")
    return "\n".join(blocks)


def collect_split(directory, systems, count, ids=None):
    cases = []
    enc = tiktoken.get_encoding("cl100k_base")
    for file in sorted(directory.glob("*/input.json")):
        folder = file.parent
        if ids is not None and folder.name not in ids:
            continue
        case, gold = read(file), read(folder / "gold.json")
        turns = {t["id"]: t for t in case["turns"]}
        results = {}
        for system in systems:
            row = read(folder / f"{system}-result.json")
            if "error" in row or (row["system"], row["question_id"]) != (
                system,
                folder.name,
            ):
                raise ValueError(f"Incomplete run: {folder.name}/{system}")
            eligible = set(row["ranking"])
            if "records" in row:
                from evidence import POOL_BUDGET, render

                previous = {}
                for t in case["turns"]:
                    if t["role"] == "user":
                        previous[t["session"]] = t["id"]
                    elif t["id"] in eligible and t["session"] in previous:
                        eligible.add(previous[t["session"]])
                pool = set(map(tuple, row["pool_sources"]))
                archive = ""
                for idx, (key, left, right) in enumerate(row["pool_sources"]):
                    t = turns[key]
                    if key not in eligible or not 0 <= left < right <= len(t["text"]):
                        raise ValueError("Invalid source span in selector input")
                    archive += (
                        f"[{idx} {t['date']} {t['role']}] {t['text'][left:right]}\n"
                    )
                pool_tokens = len(enc.encode_ordinary(archive))
                if (
                    pool_tokens != row["selector_context_tokens"]
                    or pool_tokens > POOL_BUDGET
                ):
                    raise ValueError("Selector input did not reproduce")
                chosen = [
                    tuple(span) for group in row["records"] for span in group["sources"]
                ]
                if not set(chosen) <= pool or len(set(chosen)) != len(chosen):
                    raise ValueError("Ungrounded or duplicated source span")
                used = list(dict.fromkeys(key for key, _, _ in chosen))
                tokens = len(enc.encode_ordinary(render(case, row["records"])))
            elif "window_offsets" in row:
                used = sorted(row["window_offsets"])
                tokens = len(enc.encode_ordinary(quote_context(turns, row, enc)))
            else:
                _, used, tokens = context(case, row["ranking"])
            if (
                tokens > BUDGET
                or tokens != row["context_tokens"]
                or used != row["turns"]
                or not set(used).issubset(eligible)
            ):
                raise ValueError(f"Context mismatch: {folder.name}/{system}")
            results[system] = row
        cases.append(
            {
                "id": folder.name,
                "question": case["question"],
                "reference": gold["reference"],
                "type": "abstention" if gold["abstention"] else gold["category"],
                "results": results,
            }
        )
    if len(cases) != count:
        raise ValueError(f"Expected {count} paired cases in {directory}")
    return cases


if __name__ == "__main__":
    parser = argparse.ArgumentParser()
    parser.add_argument("development", type=Path)
    parser.add_argument("holdout", type=Path)
    args = parser.parse_args()
    dev = collect_split(
        args.development,
        ["ontology", "ontology-reader", "ontology-passages", "ontology-revised"],
        4,
    )
    holdout = collect_split(args.holdout, ["ontology", "ontology-revised"], 14)
    counts = Counter(c["type"] for c in holdout)
    if len(counts) != 7 or set(counts.values()) != {2}:
        raise ValueError("Unbalanced holdout")
    if len({c["id"].removesuffix("_abs") for c in dev + holdout}) != len(dev + holdout):
        raise ValueError("Overlapping cases")

    def model_digests(directory):
        return {
            m["name"]: m["digest"]
            for m in read(directory / "models.json")["models"]
            if m["name"] in ("gemma4:26b", "all-minilm:latest")
        }

    models = model_digests(args.holdout)
    if len(models) != 2 or model_digests(args.development) != models:
        raise ValueError("Embedding or reader models differ across splits")
    print(
        json.dumps(
            {
                "dataset_sha256": DATA_SHA,
                "models": models,
                "development": dev,
                "holdout": holdout,
            },
            indent=2,
        )
    )
