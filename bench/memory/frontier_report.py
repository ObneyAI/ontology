"""Audit the model-matched and low-cost runs on the complete dataset."""

import argparse
import hashlib
from collections import defaultdict
from pathlib import Path

import tiktoken
from audit_followup import EVALUATOR_SHA
from compare import read
from frontier_batch import LUNA, SONNET
from frontier_run import READERS, choices, get_pool, verdict
from full_run import write
from pilot import BUDGET

from evidence import POOL_BUDGET, render


def summary(rows):
    scores = {}
    for label in READERS.values():
        scores[label] = {
            "complete": sum(r[label]["correct"] for r in rows),
            "limited": sum(r[label]["limited"] for r in rows),
            "wins": sum(r[label]["correct"] and not r["competitor"] for r in rows),
            "losses": sum(r["competitor"] and not r[label]["correct"] for r in rows),
        }
    return {
        "questions": len(rows),
        "readers": scores,
        "supermemory": sum(r["competitor"] for r in rows),
    }


def collect(root, out, artifact):
    m = read(root / "manifest.json")
    enc = tiktoken.get_encoding("cl100k_base")
    archive = {
        c["id"]: c["results"]["ontology-evidence"]
        for panel in ("development", "preferences", "heldout")
        for c in read(artifact / f"{panel}.json")["cases"]
    }
    rival_receipts = list(out.glob("competitor-*.live.json")) or list(
        out.glob("competitor-*.results.json")
    )
    rival_batch = read(rival_receipts[0])
    judgements = choices(rival_batch)
    if set(judgements) != set(m["ids"]):
        raise ValueError("Competitor batch IDs differ")
    rows = []
    for k in m["ids"]:
        folder = root / k
        row = read(out / (k + ".json"))
        case = read(folder / "input.json")
        gold = read(folder / "gold.json")
        comp = read(root / "competitor-cache" / (k + ".json"))
        if (
            comp["question"] != case["question"]
            or str(comp["reference"]).strip().casefold()
            != str(gold["reference"]).strip().casefold()
        ):
            raise ValueError(f"Competitor reference mismatch: {k}")
        pool = get_pool(folder, archive)
        facts = row["evidence"]
        prompt = render(case, facts["records"])
        turns = {t["id"]: t for t in case["turns"]}
        ranked = read(folder / "ontology.json")
        eligible = set(ranked["candidates"])
        prev = {}
        for t in case["turns"]:
            if t["role"] == "user":
                prev[t["session"]] = t["id"]
            elif t["id"] in ranked["candidates"] and t["session"] in prev:
                eligible.add(prev[t["session"]])
        spans = set(map(tuple, pool["spans"]))
        chosen = [
            tuple(span) for group in facts["records"] for span in group["sources"]
        ]
        if (
            not all(
                key in eligible and 0 <= lo < hi <= len(turns[key]["text"])
                for key, lo, hi in spans
            )
            or not set(chosen) <= spans
            or len(set(chosen)) != len(chosen)
            or len(enc.encode_ordinary(pool["text"])) > POOL_BUDGET
            or facts["context_tokens"] != len(enc.encode_ordinary(prompt))
            or facts["context_tokens"] > BUDGET
            or facts["context_sha256"] != hashlib.sha256(prompt.encode()).hexdigest()
        ):
            raise ValueError(f"Invalid provenance or budget for {k}")
        annotated = set(gold["annotated"])
        chosen_turns = {span[0] for span in chosen}
        pooled_turns = {span[0] for span in spans}
        rows.append(
            {
                **row,
                "question": case["question"],
                "reference": gold["reference"],
                "category": gold["category"],
                "abstention": gold["abstention"],
                "annotated_turns": len(annotated),
                "annotated_in_candidates": len(annotated.intersection(eligible)),
                "annotated_in_pool": len(annotated.intersection(pooled_turns)),
                "annotated_in_selection": len(annotated.intersection(chosen_turns)),
                "unseen": k in m["unseen"],
                "previously_run": k in m["previously_run"],
                "reranker": pool.get("reranker", "ms-marco-MiniLM-L-6-v2"),
                "blank_turns": ranked.get("blank-turns-skipped", 0),
                "provider_blocked": pool.get("provider_blocked", False),
                "competitor": verdict(judgements[k]),
                "competitor_source": comp["source"],
                "competitor_answer_sha256": hashlib.sha256(
                    comp["answer"].encode()
                ).hexdigest(),
                "competitor_published": {
                    j["model"]: j["score"] for j in comp["judges"]
                },
            }
        )
    if len(rows) != 500:
        raise ValueError("Incomplete run")
    charges = defaultdict(float)
    receipts = []
    reused = []
    for f in sorted([*out.glob("*.results.json"), *out.glob("*.live.json")]):
        b = read(f)
        if b["status"] != "completed":
            continue
        if (
            f.name.endswith(".results.json")
            and f.with_name(f.name.replace(".results.json", ".live.json")).exists()
        ):
            continue
        role = (
            "competitor judge"
            if f.name.startswith("competitor-")
            else (
                "selector"
                if "-select-" in f.name
                else "judge"
                if "-judge-" in f.name
                else b["model"].split("/")[-1]
            )
        )
        usage = b["usage"] or {}
        cost = usage.get("cost")
        if cost is None:
            cost = sum(r["usage"]["cost"] for r in choices(b).values())
        reused.extend(
            data["usage"]["cost"]
            for r in b["results"]
            if (data := r["response"]["body"]).get("reused_prior")
        )
        charges[role] += float(cost)
        receipts.append(
            {
                "id": b["id"],
                "model": b["model"],
                "role": role,
                "provider": b.get("provider"),
                "requests": b["request_counts"],
                "usage": usage,
                "cost": cost,
            }
        )
    retrieval_cost = 0
    for f in root.glob("*/hosted-embedding.json"):
        retrieval_cost += sum(u.get("cost", 0) for u in read(f)["usage"])
    for f in root.glob("*/pool.json"):
        retrieval_cost += sum(u.get("cost", 0) for u in read(f).get("usage", []))
    prior = {
        r["generation_id"] for r in read(artifact / "heldout-sonnet.json")["cases"]
    }
    abandoned = [read(f) for f in (root.parent / "sonnet-5.5-replay").glob("*.json")]
    historical = sum(
        r["usage"]["cost"] for r in abandoned if r["generation_id"] not in prior
    )
    used_receipts = {receipt["id"] for receipt in receipts}
    superseded = [
        item
        for f in sorted((out / "transition").glob("*.json"))
        if (item := read(f))["id"] not in used_receipts
    ]
    return {
        "superseded_batches": [
            {k: b.get(k) for k in ("id", "model", "status", "request_counts", "usage")}
            for b in superseded
        ],
        "dataset_sha256": m["dataset_sha256"],
        "rubric_sha256": EVALUATOR_SHA,
        "models": {
            "selector": LUNA,
            "readers": READERS,
            "judge": LUNA,
            "competitor_reader": SONNET,
        },
        "pricing": read(out / "catalog.json"),
        "all": summary(rows),
        "unseen": summary([r for r in rows if r["unseen"]]),
        "abstentions": summary([r for r in rows if r["abstention"]]),
        "retrieval_accepted": summary([r for r in rows if not r["provider_blocked"]]),
        "retrieval_blocked": summary([r for r in rows if r["provider_blocked"]]),
        "original_importer_correct": {
            l: sum(r[l]["correct"] and not r["blank_turns"] for r in rows)
            for l in READERS.values()
        },
        "by_retrieval": {
            t: summary([r for r in rows if r["reranker"] == t])
            for t in sorted({r["reranker"] for r in rows})
        },
        "by_category": {
            t: summary([r for r in rows if r["category"] == t])
            for t in sorted({r["category"] for r in rows})
        },
        "reused_reader_calls": len(reused),
        "reused_reader_cost_usd": sum(reused),
        "inference_cost_usd": dict(charges),
        "retrieval_cost_usd": retrieval_cost,
        "interrupted_reader_cost_usd": historical,
        "receipts": receipts,
        "cases": rows,
    }


def report(data):
    names = {
        "luna": "GPT-6 Luna",
        "sonnet": "Claude Sonnet 5.5",
        "deepseek": "DeepSeek V4.1 Flash 1017",
    }
    all_rows = data["cases"]
    charges = data["inference_cost_usd"]
    costs = {}
    # Use the provider's batch total when individual usage records omit costs.
    for model, label in READERS.items():
        costs[label] = sum(
            b["cost"]
            for b in data["receipts"]
            if b["role"] not in {"selector", "judge", "competitor judge"}
            and b["model"].removesuffix(":batch").startswith(model)
        )
    pending = [
        b
        for b in data["superseded_batches"]
        if b["status"] not in {"failed", "completed", "expired", "cancelled"}
    ]
    superseded_cost = sum(
        (b["usage"] or {}).get("cost", 0) for b in data["superseded_batches"]
    )
    missing_selection = sum(
        r["evidence"].get("selection_unavailable", False) for r in all_rows
    )
    misses = [
        r
        for r in all_rows
        if not r["sonnet"]["correct"] and not r["abstention"] and r["annotated_turns"]
    ]
    no_evidence = sum(not r["annotated_in_selection"] for r in misses)
    lines = [
        "# Full LongMemEval-S hosted reader comparison",
        "",
        "Each of the three readers received the same evidence for each question. GPT-6 Luna selected source records and graded complete answers against the pinned upstream rubric. We also graded the first 160 visible cl100k tokens of our answers. The comparison uses Supermemory’s published Claude Sonnet 5.5 answers, regraded by the same Luna judge on the same 500 questions.",
        "",
        "| Reader | Full answers / 500 | First 160 tokens / 500 | Unseen / 394 | Ours only / Supermemory only | Reader charges |",
        "|---|---:|---:|---:|---:|---:|",
    ]
    for label in sorted(names, key=costs.get):
        row = data["all"]["readers"][label]
        lines.append(
            f"| {names[label]} | {row['complete']} | {row['limited']} | {data['unseen']['readers'][label]['complete']} | {row['wins']} / {row['losses']} | ${costs[label]:.4f} |"
        )
    lines.extend(
        [
            f"| Supermemory · Sonnet 5.5 | {data['all']['supermemory']} | — | {data['unseen']['supermemory']} | — | Vendor charges unavailable |",
            "",
            "The unseen set excludes the 93 previously run question IDs and another 13 paired or text-equivalent questions. It also has no labeled supporting-session overlap with those earlier runs.",
            "",
            "## Where scores differ",
            "",
            "| Question type | Questions | Luna | DeepSeek | Sonnet | Supermemory |",
            "|---|---:|---:|---:|---:|",
        ]
    )
    for category, row in data["by_category"].items():
        points = [row["readers"][l]["complete"] for l in ("luna", "deepseek", "sonnet")]
        lines.append(
            f"| {category} | {row['questions']} | {points[0]} | {points[1]} | {points[2]} | {row['supermemory']} |"
        )
    lines.extend(
        [
            "",
            f"Of {len(misses)} missed Sonnet questions with annotated supporting turns that called for a substantive answer, {no_evidence} had no annotated turn in the evidence. The other {len(misses) - no_evidence} included at least one annotated turn. An excerpt can still omit a needed fact, date or event.",
            "",
            "## Retrieval and operational limits",
            "",
            "| Retrieval condition | Questions | Luna | DeepSeek | Sonnet | Supermemory |",
            "|---|---:|---:|---:|---:|",
        ]
    )
    for category, row in {
        **data["by_retrieval"],
        "Provider-declined subset": data["retrieval_blocked"],
    }.items():
        points = [row["readers"][l]["complete"] for l in ("luna", "deepseek", "sonnet")]
        lines.append(
            f"| {category} | {row['questions']} | {points[0]} | {points[1]} | {points[2]} | {row['supermemory']} |"
        )
    lines.extend(
        [
            "",
            "We preserved the 431 retrieval pools already assembled by the original MiniLM reranker. The remaining 69 used hosted NVIDIA reranking after local model inference stopped. Thirteen requests were declined and their readers received empty evidence. All 500 questions count toward the headline scores. Seven histories contained blank messages, which were skipped during graph ingestion. The response budget was 1,024 tokens including reasoning. Supermemory used its own retrieval and generation settings. These are paired system comparisons, and all three of our reader arms share these retrieval conditions.",
            "",
            f"Evidence selection was unavailable for {missing_selection} additional questions. Each reader received the question with empty evidence for those cases.",
            "",
            "## Cost",
            "",
            f"The completed comparison used ${sum(charges.values()):.4f} in receipted inference charges. This includes ${charges.get('selector', 0):.4f} for shared evidence selection, ${charges.get('judge', 0):.4f} for grading our answers and ${charges.get('competitor judge', 0):.4f} for grading the competitor. Successful hosted retrieval stages reported ${data['retrieval_cost_usd']:.4f}. The Sonnet arm reused {data['reused_reader_calls']} identical earlier calls originally billed at ${data['reused_reader_cost_usd']:.4f} in total. The reader column shows incremental charges. Superseded batch jobs have reported ${superseded_cost:.4f} so far, with {len(pending)} still pending at the accounting snapshot. The earlier interrupted reader run incurred ${data['interrupted_reader_cost_usd']:.4f} in separate charges. Local electricity and the competitor’s original inference are excluded.",
            "",
            "[Per-question answers, price snapshot and inference receipts](results.json) · [Run instructions](../../../bench/memory/README.md) · [Published Supermemory run](https://supermemory.ai/research/runs/longmemeval/) · [OpenRouter batch pricing](https://openrouter.ai/blog/announcements/batch-api/)",
        ]
    )
    return "\n".join(lines) + "\n"


if __name__ == "__main__":
    p = argparse.ArgumentParser()
    for k in ("root", "out", "artifact", "result"):
        p.add_argument(k, type=Path)
    a = p.parse_args()
    data = collect(a.root, a.out, a.artifact)
    a.result.parent.mkdir(parents=True, exist_ok=True)
    write(a.result, data)
    a.result.with_name("report.md").write_text(report(data))
    print(data["all"], data["inference_cost_usd"])
