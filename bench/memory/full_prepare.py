"""Prepare the pinned full dataset and identify questions previously evaluated."""

import argparse
import hashlib
import json
import os
import tempfile
from pathlib import Path

from compare import read
from pilot import (
    DATA_SHA,
    READER,
    SPLITS,
    api,
    prepare_question,
    save,
    stratified_questions,
)

PANELS = [
    "campaign",
    "holdout-v1",
    "followup-v1",
    "confirmation-v1",
    "quotes-v1",
    "evidence-v1",
    "preferences-v1",
]


def prepare(root, out):
    with (root / "dataset.json").open("rb") as stream:
        if hashlib.file_digest(stream, "sha256").hexdigest() != DATA_SHA:
            raise ValueError("Unexpected dataset digest")
    rows = json.loads((root / "dataset.json").read_bytes())
    if len(rows) != 500 or len({r["question_id"] for r in rows}) != 500:
        raise ValueError("Expected 500 distinct IDs")
    out.mkdir(parents=True, exist_ok=True)
    models = {m["name"]: m["digest"] for m in api("tags")["models"]}
    pinned = read(Path("evidence/benchmarks/longmemeval-s-evidence/heldout.json"))[
        "models"
    ]
    if any(models[k] != v for k, v in pinned.items()):
        raise ValueError("Pinned models differ")
    previous = {}
    for panel in PANELS:
        for f in (root / panel).glob("*/input.json"):
            previous[f.parent.name] = f.parent
    if not set(previous) <= {r["question_id"] for r in rows}:
        raise ValueError("Unknown prior question")

    def base(r):
        return r["question_id"].removesuffix("_abs")

    exposed = [
        next(
            r
            for r in rows
            if r["question_type"] == category and not r["question_id"].endswith("_abs")
        )
        for category in ("single-session-user", "multi-session", "knowledge-update")
    ]
    exposed.append(next(r for r in rows if r["question_id"].endswith("_abs")))
    for name, (salt, size) in SPLITS.items():
        exposed.extend(
            stratified_questions(
                rows,
                exposed,
                salt,
                size,
                disjoint_sources=name != "holdout",
                only="single-session-preference" if name == "preferences" else None,
            )
        )
    prior_ids = {r["question_id"] for r in exposed}
    if not set(previous) <= prior_ids:
        raise ValueError("Cached panel has unexpected IDs")
    bases = {base(r) for r in exposed}
    texts = {r["question"].strip().casefold() for r in exposed}
    sources = {s for r in exposed for s in r["answer_session_ids"]}
    manifest = {
        "dataset_sha256": DATA_SHA,
        "models": {k: models[k] for k in [READER, "all-minilm:latest"]},
        "previously_run": sorted(prior_ids),
        "unseen": [
            r["question_id"]
            for r in rows
            if base(r) not in bases and r["question"].strip().casefold() not in texts
        ],
        "unseen_sources": [
            r["question_id"]
            for r in rows
            if base(r) not in bases
            and r["question"].strip().casefold() not in texts
            and not sources.intersection(r["answer_session_ids"])
        ],
        "ids": [r["question_id"] for r in rows],
    }
    if (out / "manifest.json").exists() and read(out / "manifest.json") != manifest:
        raise ValueError("Existing manifest differs")
    save(out / "manifest.json", manifest)
    for r in sorted(rows, key=lambda r: r["question_id"] not in previous):
        key = r["question_id"]
        dest = out / key
        if dest.exists():
            continue
        with tempfile.TemporaryDirectory(dir=out) as tmp:
            stage = Path(tmp) / key
            stage.mkdir()
            if key in previous:
                for name in ("input.json", "gold.json", "embedding.json"):
                    os.link(previous[key] / name, stage / name)
                ranking = previous[key] / "ontology.json"
                if ranking.exists() and len(read(ranking)["candidates"]) >= 80:
                    os.link(ranking, stage / "ontology.json")
            else:
                prepare_question(r, stage)
            stage.rename(dest)


if __name__ == "__main__":
    parser = argparse.ArgumentParser()
    for arg in ("root", "out"):
        parser.add_argument(arg, type=Path)
    args = parser.parse_args()
    prepare(args.root, args.out)
