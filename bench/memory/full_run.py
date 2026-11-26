"""Checkpoint the frozen LongMemEval-S evidence, reader and grading pipeline."""

import argparse
import hashlib
import time
from pathlib import Path

import tiktoken
from audit_followup import judge_answer, load_prompt_builder
from compare import read
from passages import score_passages
from pilot import BUDGET, READER, api, save
from replay_sonnet import cached_answer

from evidence import build_pool, render, select_records


def write(path, data):
    tmp = path.with_suffix(".partial")
    save(tmp, data)
    tmp.replace(path)


def run(stage, root, artifact):
    manifest = read(root / "manifest.json")
    models = {m["name"]: m["digest"] for m in api("tags")["models"]}
    assert all(models[k] == v for k, v in manifest["models"].items())
    codec = tiktoken.get_encoding("cl100k_base")
    rubric = load_prompt_builder(root.parent / "upstream_evaluate_qa.py")
    cache = root.parent / "sonnet-5.5-replay"
    cache.mkdir(exist_ok=True)
    archive = {}
    for panel in ("development", "preferences", "heldout"):
        for c in read(artifact / f"{panel}.json")["cases"]:
            archive[c["id"]] = c["results"]["ontology-evidence"]
    stages = {
        "pool": ("ontology", "pool"),
        "select": ("pool", "evidence"),
        "answer": ("evidence", "hosted"),
        "grade": ("hosted", "grade"),
        "competitor": ("supermemory", "competitor-grade"),
    }
    source, dest = stages[stage]

    def process(folder):
        key = folder.name
        input_row = read(folder / f"{source}.json")
        if stage in ("grade", "competitor"):
            gold = read(folder / "gold.json")
            question = read(folder / "input.json")["question"]

            def verdict(answer):
                return bool(answer) and judge_answer(
                    rubric(
                        gold["category"],
                        question,
                        gold["reference"],
                        answer,
                        abstention=gold["abstention"],
                    )
                )

            answer = input_row["answer"]
            complete = verdict(answer)
            if stage == "competitor":
                return {"correct": complete}
            visible = codec.encode_ordinary(answer)
            limited = (
                verdict(codec.decode(visible[:160])) if len(visible) > 160 else complete
            )
            return {
                "complete": complete,
                "limited": limited,
                "visible_tokens": len(visible),
            }
        case = read(folder / "input.json")
        if stage == "pool":
            if key in archive and archive[key]["ranking"] == input_row["candidates"]:
                return {"archived": True}
            ranked = score_passages(case, input_row["candidates"], case["question"])
            spans, text = build_pool(case, ranked, codec)
            return {"spans": spans, "text": text}
        if stage == "select":
            if input_row.get("archived"):
                row = archive[key]
                details = {
                    k: row[k]
                    for k in (
                        "records",
                        "mode",
                        "selector_usage",
                        "selector_context_tokens",
                        "pool_sources",
                    )
                }
                prompt = render(case, details["records"])
                assert len(codec.encode_ordinary(prompt)) == row["context_tokens"]
            else:
                prompt, _, _, details = select_records(
                    case,
                    input_row["spans"],
                    input_row["text"],
                    codec,
                    BUDGET,
                    api,
                    READER,
                )
            assert len(codec.encode_ordinary(prompt)) <= BUDGET
            return {
                **details,
                "context_sha256": hashlib.sha256(prompt.encode()).hexdigest(),
            }
        context = render(case, input_row["records"])
        assert (
            hashlib.sha256(context.encode()).hexdigest() == input_row["context_sha256"]
        )
        response, request_hash, _ = cached_answer(
            key, case, context, input_row["mode"], cache
        )
        return {**response, "request_sha256": request_hash}

    pending = list(manifest["ids"])
    attempts = {}
    while pending:
        remaining = []
        progressed = False
        for key in pending:
            folder = root / key
            target = folder / f"{dest}.json"
            if target.exists():
                continue
            if not (folder / f"{source}.json").exists():
                remaining.append(key)
                continue
            try:
                write(target, process(folder))
            except Exception as exc:
                attempts[key] = attempts.get(key, 0) + 1
                print(stage, key, type(exc).__name__, str(exc)[:500], flush=True)
                if attempts[key] >= 3:
                    raise
                remaining.append(key)
                continue
            progressed = True
            print(stage, key, flush=True)
        pending = remaining
        if pending and not progressed:
            time.sleep(2)


if __name__ == "__main__":
    parser = argparse.ArgumentParser()
    parser.add_argument(
        "stage", choices=["pool", "select", "answer", "grade", "competitor"]
    )
    parser.add_argument("root", type=Path)
    parser.add_argument("artifact", type=Path)
    args = parser.parse_args()
    run(args.stage, args.root, args.artifact)
