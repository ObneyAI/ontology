"""Publish paired results and trace every accepted revision to its source and grade."""

import argparse
import hashlib
from pathlib import Path

from compare import read
from frontier_run import choices, verdict
from full_run import write
from parity_report import paired


def receipt(directory, label):
    (path,) = directory.glob(label + "-*.live.json")
    return choices(read(path))


def publish(root, baseline, review, decisions, dest):
    prior = read(baseline)
    original = {r["id"]: r for r in prior["cases"]}
    by_panel, traces, costs, experiments = {}, [], {}, {}
    manifest = read(root / "manifest.json")
    for label in ("dev", "remaining"):
        final = read(decisions / (label + ".json"))
        trial = read(review / (label + ".json"))
        for k in ("dataset_sha256", "rubric_sha256", "judge"):
            if final[k] != prior[k] or trial[k] != prior[k]:
                raise ValueError(f"Changed grading protocol: {k}")
        grades = receipt(review, label + "-judge")
        proposed = receipt(review, label + "-review")
        choices_ab = receipt(decisions, label + "-decide")
        by_panel[label] = final["cases"]
        experiments[label] = {
            "review_only": trial["summary"]["correct"],
            "with_decision": final["summary"]["correct"],
        }
        for key, charge in final["review_cost_usd"].items():
            costs[key] = costs.get(key, 0.0) + charge
        for r in final["cases"]:
            k = r["id"]
            old = original[k]
            if (k in manifest["previously_run"]) != (label == "dev"):
                raise ValueError(f"Wrong split for {k}")
            if (
                r["competitor"] != old["competitor"]
                or r["pre_review"] != old["correct"]
            ):
                raise ValueError(f"Changed comparison for {k}")
            if old["reused"] and any(
                k in responses for responses in (proposed, grades, choices_ab)
            ):
                raise ValueError(f"Unavailable source used for {k}")
            trace = {
                "id": k,
                "accepted_revision": r["accepted_revision"],
                "correct": r["correct"],
            }
            if r["accepted_revision"]:
                case = read(root / k / "input.json")
                flip = hashlib.sha256(case["question"].encode()).digest()[0] % 2
                expected = "B" if flip else "A"
                final_vote = (
                    choices_ab[k]["answer"]
                    .strip()
                    .splitlines()[-1]
                    .strip()
                    .upper()
                    .rstrip(".!")
                )
                if final_vote != expected or choices_ab[k]["finish_reason"] != "stop":
                    raise ValueError(f"Revision lacks an affirmative decision in {k}")
                valid = {t["id"] for t in case["turns"]}
                if (
                    not set(r["review_sources"]) <= valid
                    or proposed[k]["answer"] != r["answer"]
                    or verdict(grades[k]) != r["correct"]
                    or k not in choices_ab
                ):
                    raise ValueError(f"Invalid revision in {k}")
                trace.update(answer=r["answer"], review_sources=r["review_sources"])
            elif (r["answer"], r["correct"]) != (old["answer"], old["correct"]):
                raise ValueError(f"Invalid fallback in {k}")
            traces.append(trace)
    rows = by_panel["dev"] + by_panel["remaining"]
    if len(rows) != 500 or {r["id"] for r in rows} != set(manifest["ids"]):
        raise ValueError("Incomplete benchmark")
    groups = {
        "All questions": rows,
        "Development": by_panel["dev"],
        "Remaining": by_panel["remaining"],
        "Previously designated unseen": [r for r in rows if r["unseen"]],
    }
    for category in sorted({r["category"] for r in rows}):
        groups[category] = [r for r in rows if r["category"] == category]
    groups["With usable evidence"] = [r for r in rows if not r["reused"]]
    groups["Unavailable histories"] = [r for r in rows if r["reused"]]
    summary = {key: paired(group) for key, group in groups.items()}
    for key, group in groups.items():
        summary[key]["before_review"] = sum(r["pre_review"] for r in group)
    campaign = sum(prior["incremental_cost_usd"].values()) + sum(costs.values())
    coverage = {"complete": 0, "partial": 0, "none": 0}
    for r in groups["With usable evidence"]:
        if r["correct"]:
            continue
        gold = read(root / r["id"] / "gold.json")
        annotated = set(gold["annotated"])
        if not annotated:
            continue
        ids = {s[0] for s in r["evidence"]["sources"]}
        if r["accepted_revision"]:
            ids.update(r["review_sources"])
        overlap = annotated & ids
        key = "complete" if overlap == annotated else "partial" if overlap else "none"
        coverage[key] += 1
    dest.mkdir(exist_ok=True, parents=True)
    write(
        dest / "review.json",
        {
            "dataset_sha256": prior["dataset_sha256"],
            "rubric_sha256": prior["rubric_sha256"],
            "selector": final["selector"],
            "reviewer": final["reviewer"],
            "decider": final["decider"],
            "judge": prior["judge"],
            "summary": summary,
            "review_cost_usd": costs,
            "campaign_cost_usd": campaign,
            "experiments": experiments,
            "annotated_turn_coverage_on_available_misses": coverage,
            "cases": traces,
        },
    )
    lines = [
        "# Source review on LongMemEval-S",
        "",
        "Each proposed revision is grounded in a second search through sessions reached by the ontology. Claude Haiku 5.5 selects relevant turns in small groups. GPT-6.1 Terra receives the selected turns and the first answer. When Terra proposes a change, Haiku chooses between the two answers after reading both sets of evidence. Candidate order depends only on the question text. Reference answers enter only during scoring.",
        "",
        "| Panel | Questions | Before review | Reviewed | Supermemory | Only ours correct | Only Supermemory correct |",
        "|---|---:|---:|---:|---:|---:|---:|",
    ]
    for key, s in summary.items():
        lines.append(
            f"| {key} | {s['n']} | {s['before_review']} | {s['correct']} | {s['supermemory']} | {s['ours_only']} | {s['supermemory_only']} |"
        )
    lines += [
        "",
        f"On the 93 development questions, accepting the reviewer’s revisions directly scored {experiments['dev']['review_only']}. Rechecking changes using both sets of evidence scored {experiments['dev']['with_decision']}. The latter configuration was frozen before the remaining 407. All 15 histories with unavailable evidence retain their original answers and count in every full score. Both systems use the same saved GPT-6 Luna verdicts for unchanged answers.",
        "",
        f"Of the missed answerable questions with usable evidence, the reader inputs included excerpts of every annotated turn in {coverage['complete']} cases, some in {coverage['partial']}, and none in {coverage['none']}. A turn can be included as an excerpt, so this check does not establish that the reader saw its entire text.",
        "",
        f"This review spent ${sum(costs.values()):.4f} on demand. Total incremental charges for the control, reader comparison and review experiments were ${campaign:.4f}. These totals exclude previously paid ingestion, retrieval and comparison runs.",
        "",
        "[Review decisions and changed answers](review.json) · [Original routed answers, comparison and source spans](results.json) · [Supermemory’s published runs](https://supermemory.ai/research/runs/longmemeval/)",
    ]
    (dest / "review.md").write_text("\n".join(lines) + "\n")
    print(summary["All questions"], coverage, campaign)


if __name__ == "__main__":
    p = argparse.ArgumentParser()
    for name in ("root", "baseline", "review", "decisions", "dest"):
        p.add_argument(name, type=Path)
    publish(**vars(p.parse_args()))
