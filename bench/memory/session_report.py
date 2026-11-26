"""Summarize the paired DeepSeek comparison and verify source excerpts."""

import argparse
import hashlib
from pathlib import Path

import tiktoken
from compare import read
from full_run import write
from session_reader import CONTEXT_LIMIT


def scores(rows):
    return {
        "questions": len(rows),
        **{k: sum(r[k] for r in rows) for k in ("baseline", "correct", "competitor")},
        "wins": sum(r["correct"] and not r["baseline"] for r in rows),
        "losses": sum(r["baseline"] and not r["correct"] for r in rows),
    }


def publish(root, run, dest):
    panels = [read(run / (p + ".json")) for p in ("development", "validation")]
    rows = [row for panel in panels for row in panel["cases"]]
    manifest = read(root / "manifest.json")
    assert len(rows) == len({r["id"] for r in rows}) == 500 and {
        r["id"] for r in rows
    } == set(manifest["ids"])
    codec = tiktoken.get_encoding("cl100k_base")
    for row in rows:
        evidence = row["evidence"]
        if row["reused"]:
            assert evidence is None and row["correct"] == row["baseline"]
            continue
        turns = {t["id"]: t for t in read(root / row["id"] / "input.json")["turns"]}
        chunks = []
        for key, left, right in evidence["sources"]:
            t = turns[key]
            assert 0 <= left < right <= len(t["text"])
            chunks.append(f"[{key} {t['date']} {t['role']}] {t['text'][left:right]}\n")
        context = "".join(chunks)
        assert (
            hashlib.sha256(context.encode()).hexdigest() == evidence["context_sha256"]
        )
        assert (
            len(codec.encode_ordinary(context))
            == evidence["context_tokens"]
            <= CONTEXT_LIMIT
        )
    groups = {
        "All questions": rows,
        "Development": panels[0]["cases"],
        "Remaining questions": panels[1]["cases"],
        "Previously designated unseen subset": [r for r in rows if r["unseen"]],
    }
    for cat in sorted({r["category"] for r in rows}):
        groups[cat] = [r for r in rows if r["category"] == cat]
    summary = {k: scores(v) for k, v in groups.items()}
    reader = sum(p["reader_cost"] for p in panels)
    judge = sum(p["judge_cost"] for p in panels)
    reuse = sum(r["reused"] for r in rows)
    dest.mkdir(parents=True, exist_ok=True)
    write(
        dest / "results.json",
        {
            **{
                k: panels[0][k]
                for k in ("dataset_sha256", "rubric_sha256", "model", "judge")
            },
            "reader_cost_usd": reader,
            "judge_cost_usd": judge,
            "summary": summary,
            "cases": rows,
        },
    )
    lines = [
        "# LongMemEval-S with conversation context",
        "",
        "Both runs use DeepSeek V4.1 Flash 1017 at low reasoning effort with a 1,024-token output limit. GPT-6 Luna grades complete answers with the same upstream rubric. Supermemory’s published Sonnet answers use the same saved judge verdicts as the baseline.",
        "",
        "| Panel | Questions | Previous DeepSeek | Revised DeepSeek | Gained | Lost | Supermemory |",
        "|---|---:|---:|---:|---:|---:|---:|",
    ]
    for k, s in summary.items():
        lines.append(
            f"| {k} | {s['questions']} | {s['baseline']} | {s['correct']} | {s['wins']} | {s['losses']} | {s['competitor']} |"
        )
    lines.extend(
        [
            "",
            "The revised reader interleaves graph hits and reranked source turns, restores user turns from the highest-ranked sessions, and reads dated excerpts in conversation order. It uses up to 8,192 evidence tokens. The previous reader used up to 1,200 after a separate selection call. Context selection and the reader instruction changed together, so their individual contributions have not been measured.",
            "",
            f"The experiment issued {len(rows) - reuse} new reader requests. It carried forward the original empty-evidence answers for {reuse} operationally unavailable histories. Exact matches to earlier answers reuse their verdicts. All 500 cases count. Development used the 93 previously exercised IDs. The remaining 407 were evaluated with the frozen implementation. The 394-question subset was designated unseen before the earlier full benchmark and has since contributed to reported results.",
            "",
            f"On-demand reader charges were ${reader:.4f} and judging cost ${judge:.4f}, totaling ${reader + judge:.4f}. This experiment reused the retrieval artifacts and competitor judgments. The revised reader requires no separate evidence-selection inference call.",
            "",
            "[Per-question evidence and scores](results.json) · [Previous full comparison](../longmemeval-s-full/report.md)",
        ]
    )
    (dest / "report.md").write_text("\n".join(lines) + "\n")
    print(summary["All questions"], reader, judge)


if __name__ == "__main__":
    parser = argparse.ArgumentParser()
    for name in ("root", "run", "dest"):
        parser.add_argument(name, type=Path)
    publish(**vars(parser.parse_args()))
