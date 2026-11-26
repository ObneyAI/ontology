"""Score a fixed reader on the current ontology ranking against the paired competitor."""

import argparse
import hashlib
import json
from pathlib import Path

import tiktoken
from audit_followup import EVALUATOR_SHA, load_prompt_builder
from compare import read
from frontier_batch import LUNA
from frontier_live import live
from frontier_run import choices, get_pool, verdict
from full_run import write
from parity_reader import INSTRUCTION, TASK, pack, task_for
from session_reader import MODEL, SYSTEM, conversation
from session_report import scores


def evaluate(root, rankings, archive_dir, previous, out, panel, mode, model):
    manifest = read(root / "manifest.json")
    prior = read(previous)
    prior_rows = {r["id"]: r for r in prior["cases"]}
    ranked = {
        r["id"]: r["candidates"]
        for r in map(json.loads, rankings.read_text().splitlines())
    }
    ids = manifest["ids"]
    if set(prior_rows) != set(ids) or set(ranked) != set(ids) or len(ids) != 500:
        raise ValueError("Incomplete input")
    if (
        prior["dataset_sha256"] != manifest["dataset_sha256"]
        or prior["judge"] != LUNA
        or prior["rubric_sha256"] != EVALUATOR_SHA
    ):
        raise ValueError(
            "The reference answers use a different dataset or grading protocol"
        )
    if panel != "all":
        ids = [k for k in ids if (k in manifest["previously_run"]) == (panel == "dev")]
    archive = {
        c["id"]: c["results"]["ontology-evidence"]
        for p in ("development", "preferences", "heldout")
        for c in read(archive_dir / (p + ".json"))["cases"]
    }
    codec = tiktoken.get_encoding("cl100k_base")
    rubric = load_prompt_builder(root.parent / "upstream_evaluate_qa.py")
    out.mkdir(parents=True, exist_ok=True)
    requests, evidence = {}, {}
    for k in ids:
        r = prior_rows[k]
        if r["reused"]:  # Permanently preserve the 15 unavailable source histories.
            continue
        if r.get("finish_reason") == "content_filter":
            evidence[k] = r["evidence"]
            continue
        case = read(root / k / "input.json")
        pool = get_pool(root / k, archive)
        task = task_for(case["question"])
        if mode == "wide" and task == "recall" and r["answer"].strip():
            evidence[k] = r["evidence"]
            continue
        if mode == "wide":
            context, spans = pack(case, pool["spans"], ranked[k], codec)
        else:
            context, spans = conversation(case, pool["spans"], ranked[k], codec)
        context_hash = hashlib.sha256(context.encode()).hexdigest()
        evidence[k] = {
            "sources": spans,
            "context_sha256": context_hash,
            "context_tokens": len(codec.encode_ordinary(context)),
        }
        if (
            mode == "current"
            and prior.get("mode", "current") == "current"
            and model == prior["model"]
            and context_hash == r["evidence"]["context_sha256"]
        ):
            continue
        payload = {
            "question": case["question"],
            "date": case["date"],
            "conversation": context,
        }
        requests[k] = {
            "messages": [
                {
                    "role": "system",
                    "content": SYSTEM
                    if mode == "current"
                    else INSTRUCTION + TASK[task],
                },
                {"role": "user", "content": json.dumps(payload)},
            ],
            "reasoning": {"effort": "low", "exclude": True},
            "max_tokens": 1024 if mode == "current" else 4096,
        }
    receipt = live(out, panel + "-reader", model, requests)
    answers = choices(receipt)
    judges = {}
    for k, response in answers.items():
        if response["answer"] == prior_rows[k]["answer"]:
            continue
        g = read(root / k / "gold.json")
        prompt = rubric(
            g["category"],
            read(root / k / "input.json")["question"],
            g["reference"],
            response["answer"],
            abstention=g["abstention"],
        )
        judges[k] = {
            "messages": [{"role": "user", "content": prompt}],
            "reasoning": {"effort": "none"},
            "max_tokens": 20,
        }
    jr = live(out, panel + "-judge", LUNA, judges)
    marks = choices(jr)
    rows = []
    for k in ids:
        r = prior_rows[k]
        rows.append(
            {
                "id": k,
                "category": r["category"],
                "unseen": r["unseen"],
                "baseline": r["correct"],
                "competitor": r["competitor"],
                "correct": verdict(marks[k]) if k in marks else r["correct"],
                "reused": r["reused"],
                "answer": answers[k]["answer"] if k in answers else r["answer"],
                "finish_reason": answers[k]["finish_reason"]
                if k in answers
                else r.get("finish_reason"),
                "evidence": evidence.get(k),
            }
        )
    result = {
        "mode": mode,
        "model": model,
        "judge": LUNA,
        "dataset_sha256": manifest["dataset_sha256"],
        "rubric_sha256": prior["rubric_sha256"],
        "reader_cost_usd": receipt["usage"]["cost"],
        "judge_cost_usd": jr["usage"]["cost"],
        "reader_calls": len(answers),
        "judge_calls": len(marks),
        "summary": scores(rows),
        "cases": rows,
    }
    write(out / (panel + ".json"), result)
    print(result["summary"], flush=True)


if __name__ == "__main__":
    p = argparse.ArgumentParser()
    for field in ("root", "rankings", "archive_dir", "previous", "out"):
        p.add_argument(field, type=Path)
    p.add_argument("panel", choices=["all", "dev", "remaining"])
    p.add_argument("--mode", choices=["current", "wide"], default="current")
    p.add_argument("--model", default=MODEL)
    evaluate(**vars(p.parse_args()))
