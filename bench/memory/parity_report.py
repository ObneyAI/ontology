"""Verify the recorded source spans and compare two answer sets on the same IDs."""

import argparse
import hashlib
from collections import Counter
from pathlib import Path

import tiktoken
from compare import read
from frontier_run import choices
from full_run import write
from parity_reader import LIMIT
from session_reader import CONTEXT_LIMIT


def paired(rows):
    return {
        "n": len(rows),
        "control": sum(r["baseline"] for r in rows),
        "correct": sum(r["correct"] for r in rows),
        "supermemory": sum(r["competitor"] for r in rows),
        "ours_only": sum(r["correct"] and not r["competitor"] for r in rows),
        "supermemory_only": sum(r["competitor"] and not r["correct"] for r in rows),
    }


def verify_source(row, case, codec):
    turns = {t["id"]: t for t in case["turns"]}
    evidence = row["evidence"]
    chunks = []
    for k, start, end in evidence["sources"]:
        t = turns[k]
        if not 0 <= start < end <= len(t["text"]):
            raise ValueError(f"Invalid span in {row['id']}")
        chunks.append(f"[{k} {t['date']} {t['role']}] {t['text'][start:end]}\n")
    context = "".join(chunks)
    if (
        hashlib.sha256(context.encode()).hexdigest() != evidence["context_sha256"]
        or len(codec.encode_ordinary(context)) != evidence["context_tokens"]
        or evidence["context_tokens"] > LIMIT
    ):
        raise ValueError(f"Evidence mismatch in {row['id']}")


def publish(root, control_path, run, dest, pilot):
    manifest = read(root / "manifest.json")
    control = read(control_path)
    panels = [read(run / (panel + ".json")) for panel in ("dev", "remaining")]
    original = {r["id"]: r for r in control["cases"]}
    ids = set(manifest["ids"])
    rows = [row for panel in panels for row in panel["cases"]]
    if len(rows) != 500 or {r["id"] for r in rows} != ids or set(original) != ids:
        raise ValueError("Expected the same 500 unique question IDs")
    if {r["id"] for r in panels[0]["cases"]} != set(manifest["previously_run"]):
        raise ValueError("Development split mismatch")
    for p in panels:
        for k in ("mode", "model", "judge", "dataset_sha256", "rubric_sha256"):
            if p[k] != panels[0][k]:
                raise ValueError(f"Inconsistent {k}")
        for k in ("judge", "dataset_sha256", "rubric_sha256"):
            if p[k] != control[k]:
                raise ValueError(f"Inconsistent control {k}")
    if manifest["dataset_sha256"] != control["dataset_sha256"]:
        raise ValueError("Dataset mismatch")
    codec = tiktoken.get_encoding("cl100k_base")
    models = Counter()
    for r in rows:
        k = r["id"]
        old = original[k]
        for key in ("competitor", "reused", "unseen", "category"):
            if r[key] != old[key]:
                raise ValueError(f"Changed {key} in {k}")
        if r["baseline"] != old["correct"]:
            raise ValueError(f"Control mismatch in {k}")
        if r["reused"]:
            if (
                r["answer"] != old["answer"]
                or r["correct"] != old["correct"]
                or r["evidence"] is not None
            ):
                raise ValueError(f"Changed unavailable history in {k}")
        else:
            verify_source(r, read(root / k / "input.json"), codec)
    for name, panel in zip(("dev", "remaining"), panels):
        files = list(run.glob(f"{name}-reader-*.live.json"))
        receipts = [read(p) for p in files]
        if len(receipts) != 1:
            raise ValueError(f"Expected one frozen reader configuration for {name}")
        issued = choices(receipts[0])
        if len(issued) != panel["reader_calls"]:
            raise ValueError("Reader call count mismatch")
        for r in panel["cases"]:
            if r["id"] in issued:
                if r["reused"]:
                    raise ValueError("Unavailable history was submitted")
                response = issued[r["id"]]
                if r["answer"] != response["answer"]:
                    raise ValueError(f"Answer does not match receipt in {r['id']}")
                r["finish_reason"] = response["finish_reason"]
                r["reader_model"] = panel["model"]
            else:
                old = original[r["id"]]
                if (r["answer"], r["correct"], r["evidence"]) != (
                    old["answer"],
                    old["correct"],
                    old["evidence"],
                ):
                    raise ValueError("Invalid reuse")
                r["reader_model"] = (
                    control["model"]
                    if not r["reused"]
                    else "archived empty-evidence answer"
                )
            models[r["reader_model"]] += 1
    groups = {
        "All questions": rows,
        "Development": panels[0]["cases"],
        "Remaining": panels[1]["cases"],
        "Previously designated unseen": [r for r in rows if r["unseen"]],
    }
    for cat in sorted({r["category"] for r in rows}):
        groups[cat] = [r for r in rows if r["category"] == cat]
    groups["Unavailable histories"] = [r for r in rows if r["reused"]]
    summary = {label: paired(group) for label, group in groups.items()}
    costs = {
        "control": control["reader_cost_usd"] + control["judge_cost_usd"],
        "development_alternatives": sum(
            read(p)["reader_cost_usd"] + read(p)["judge_cost_usd"] for p in pilot
        ),
        "routed_reader": sum(p["reader_cost_usd"] for p in panels),
        "routed_judge": sum(p["judge_cost_usd"] for p in panels),
    }
    dest.mkdir(parents=True, exist_ok=True)
    write(
        dest / "results.json",
        {
            **{
                k: panels[0][k]
                for k in ("model", "mode", "judge", "dataset_sha256", "rubric_sha256")
            },
            "reader_counts": dict(models),
            "incremental_cost_usd": costs,
            "summary": summary,
            "cases": rows,
        },
    )
    lines = [
        "# LongMemEval-S answer comparison",
        "",
        "Supermemory’s published Claude Sonnet 5.5 answers and our answers were graded with the same pinned upstream prompts and GPT-6 Luna judge. Each of the 500 questions contributes one point. [Supermemory run archive](https://supermemory.ai/research/runs/longmemeval/)",
        "",
        "| Panel | Questions | DeepSeek control | Routed adapter | Supermemory | Only ours correct | Only Supermemory correct |",
        "|---|---:|---:|---:|---:|---:|---:|",
    ]
    for label, s in summary.items():
        lines.append("| " + " | ".join([label, *(str(x) for x in s.values())]) + " |")
    lines += [
        "",
        f"The control uses the current ontology rankings and an {CONTEXT_LIMIT:,}-token conversation reader. The earlier version scored 434/500. The routed adapter uses the question wording to recognize synthesis and advice requests. It also retries empty answers from the control that exhausted their output budget. These routes use Claude Haiku 5.5 with up to {LIMIT:,} evidence tokens and 4,096 output tokens. It preserves the other DeepSeek V4.1 Flash answers. Context includes dated source excerpts from native ontology hits, the archived reranked passage pool and their linked sessions.",
        "",
        f"The adapter sent {panels[0]['reader_calls'] + panels[1]['reader_calls']} questions to Haiku. All 15 unavailable histories retain the original empty-evidence answers. The 93 development questions guided model selection. We froze that choice before grading the remaining 407. The project has evaluated these benchmark questions before.",
        "",
        f"This campaign spent ${sum(costs.values()):.4f} on demand, including ${costs['control']:.4f} to update the control and ${costs['development_alternatives']:.4f} for the alternative development reader. The selected reader and its judge cost ${costs['routed_reader']:.4f} and ${costs['routed_judge']:.4f}, respectively. These incremental charges reuse the existing retrieval, prior answers and competitor verdicts.",
        "",
        "[Per-question answers and source spans](results.json) · [Earlier 434/500 run](../longmemeval-s-session-reader/results.json)",
    ]
    (dest / "report.md").write_text("\n".join(lines) + "\n")
    print(summary["All questions"], costs)


if __name__ == "__main__":
    parser = argparse.ArgumentParser()
    for name in ("root", "control_path", "run", "dest"):
        parser.add_argument(name, type=Path)
    parser.add_argument("--pilot", type=Path, action="append", default=[])
    publish(**vars(parser.parse_args()))
