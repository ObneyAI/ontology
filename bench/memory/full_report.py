"""Verify the full campaign, publish paired results and summarize shared-judge scores."""

import argparse
import hashlib
from pathlib import Path

import tiktoken
from audit_followup import EVALUATOR_SHA
from compare import read
from full_run import write
from pilot import BUDGET, READER
from replay_sonnet import MODEL

from evidence import POOL_BUDGET, render


def summarize(rows):
    return {
        "n": len(rows),
        "complete": sum(r["complete"] for r in rows),
        "limited": sum(r["limited"] for r in rows),
        "competitor": sum(r["competitor_correct"] for r in rows),
        "ours_only": sum(r["complete"] and not r["competitor_correct"] for r in rows),
        "theirs_only": sum(r["competitor_correct"] and not r["complete"] for r in rows),
        "neither": sum(not r["competitor_correct"] and not r["complete"] for r in rows),
    }


def collect(root, artifact):
    manifest = read(root / "manifest.json")
    codec = tiktoken.get_encoding("cl100k_base")
    members = set(manifest["unseen"])
    prior_replay = {r["id"]: r for r in read(artifact / "heldout-sonnet.json")["cases"]}
    rows = []
    for key in manifest["ids"]:
        folder = root / key
        case = read(folder / "input.json")
        gold = read(folder / "gold.json")
        selected = read(folder / "evidence.json")
        hosted = read(folder / "hosted.json")
        grade = read(folder / "grade.json")
        rival = read(folder / "supermemory.json")
        ranked = read(folder / "ontology.json")
        ranking = ranked["candidates"]
        turns = {t["id"]: t for t in case["turns"]}
        eligible = set(ranking)
        prior = {}
        for t in case["turns"]:
            if t["role"] == "user":
                prior[t["session"]] = t["id"]
            elif t["id"] in ranking and t["session"] in prior:
                eligible.add(prior[t["session"]])
        pool = set(map(tuple, selected["pool_sources"]))
        for turn, left, right in pool:
            if turn not in eligible or not 0 <= left < right <= len(
                turns[turn]["text"]
            ):
                raise ValueError(f"Invalid selector source: {key}")
        spans = [tuple(s) for group in selected["records"] for s in group["sources"]]
        context = render(case, selected["records"])
        context_tokens = len(codec.encode_ordinary(context))
        if (
            len(set(spans)) != len(spans)
            or not set(spans) <= pool
            or context_tokens > BUDGET
            or selected["selector_context_tokens"] > POOL_BUDGET
            or selected["context_sha256"]
            != hashlib.sha256(context.encode()).hexdigest()
            or case["question"] != rival["question"]
            or str(gold["reference"]).strip().casefold()
            != str(rival["reference"]).strip().casefold()
            or hosted["model"] != MODEL
        ):
            raise ValueError(f"Validation failed: {key}")
        if (
            key in prior_replay
            and hosted["request_sha256"] != prior_replay[key]["request_sha256"]
        ):
            raise ValueError(f"Prior reader request changed: {key}")
        annotated = set(gold["annotated"])
        selected_turns = {s[0] for s in spans}
        rows.append(
            {
                "id": key,
                "question": case["question"],
                "previously_run": key in manifest["previously_run"],
                "reused_generation": key in prior_replay
                and hosted["generation_id"] == prior_replay[key]["generation_id"],
                "reference": gold["reference"],
                "category": gold["category"],
                "abstention": gold["abstention"],
                "unseen": key in members,
                "reader_mode": selected["mode"],
                "context_sha256": selected["context_sha256"],
                "request_sha256": hosted["request_sha256"],
                "generation_id": hosted["generation_id"],
                "provider": hosted["provider"],
                "usage": hosted["usage"],
                "finish_reason": hosted["finish_reason"],
                "answer": hosted["answer"],
                **grade,
                "context_tokens": context_tokens,
                "selector_usage": selected["selector_usage"],
                "blank_turns_skipped": ranked.get("blank-turns-skipped", 0),
                "records": selected["records"],
                "annotated_turns": len(annotated),
                "ranked_annotated": len(annotated.intersection(ranking)),
                "selected_annotated": len(annotated & selected_turns),
                "competitor_source": rival["source"],
                "competitor_answer_sha256": hashlib.sha256(
                    rival["answer"].encode()
                ).hexdigest(),
                "competitor_correct": read(folder / "competitor-grade.json")["correct"],
                "competitor_published": bool(
                    next(
                        j["score"]
                        for j in rival["judges"]
                        if j["model"] == "gpt-4o-2024-08-06"
                    )
                ),
            }
        )
    if len(rows) != 500 or len({r["generation_id"] for r in rows}) != len(rows):
        raise ValueError("Incomplete or duplicated hosted answers")
    return {
        "dataset_sha256": manifest["dataset_sha256"],
        "reader": MODEL,
        "judge": READER,
        "models": manifest["models"],
        "rubric_sha256": EVALUATOR_SHA,
        "all": summarize(rows),
        "unseen": summarize([r for r in rows if r["unseen"]]),
        "abstention": summarize([r for r in rows if r["abstention"]]),
        "by_type": {
            t: summarize([r for r in rows if r["category"] == t])
            for t in sorted({r["category"] for r in rows})
        },
        "cost_usd": round(sum(r["usage"]["cost"] for r in rows), 8),
        "reused_cost_usd": round(
            sum(r["usage"]["cost"] for r in rows if r["reused_generation"]), 8
        ),
        "original_importer_correct": sum(
            r["complete"] for r in rows if not r["blank_turns_skipped"]
        ),
        "competitor_published": sum(r["competitor_published"] for r in rows),
        "cases": rows,
    }


if __name__ == "__main__":
    p = argparse.ArgumentParser()
    for arg in ("root", "artifact", "output"):
        p.add_argument(arg, type=Path)
    a = p.parse_args()
    data = collect(a.root, a.artifact)
    a.output.parent.mkdir(parents=True, exist_ok=True)
    write(a.output, data)
    print({k: v for k, v in data.items() if k != "cases"})
