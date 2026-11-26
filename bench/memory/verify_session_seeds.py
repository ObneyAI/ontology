"""Compare two native ontology rankings at a fixed cutoff, before reading answers."""

import argparse
import hashlib
import json
from pathlib import Path


def read(path):
    return json.loads(path.read_bytes())


def compare(root, replay, baseline_replay=None):
    manifest = read(root / "manifest.json")
    expected = set(manifest["ids"])
    previous = {}
    baseline_kind = None
    policy = "distinct-sessions-v1"
    if baseline_replay is not None:
        with baseline_replay.open() as stream:
            for line in stream:
                row = json.loads(line)
                kind = row.get("policy", "session-only")
                if (
                    kind not in {"session-only", "session-bm25", "session-date"}
                    or row["id"] in previous
                ):
                    raise ValueError("Unexpected or duplicate baseline row")
                if baseline_kind is not None and baseline_kind != kind:
                    raise ValueError("Mixed policies in baseline replay")
                baseline_kind = kind
                previous[row["id"]] = row
        if previous.keys() != expected:
            raise ValueError("Incomplete baseline replay")
        policy = {
            "session-only": "session-bm25",
            "session-bm25": "session-date",
            "session-date": "session-aggregate",
        }[baseline_kind]
    cases = {}
    changed_rankings = 0
    routed_field = {
        "session-date": "date-routed",
        "session-aggregate": "aggregate-routed",
    }.get(policy)
    routed_count = 0
    summary = {
        name: {"answerable": 0, "complete": 0, "any": 0, "turns": 0}
        for name in ("original", "revised")
    }
    changes = []
    with replay.open() as stream:
        for line in stream:
            row = json.loads(line)
            key = row["id"]
            if key not in expected or key in cases:
                raise ValueError(f"Unexpected or duplicate replay ID {key}")
            folder = root / key
            turns = {
                t["id"]: t
                for t in read(folder / "input.json")["turns"]
                if t["text"].strip()
            }
            old = read(folder / "ontology.json")
            before = previous[key]["candidates"] if previous else old["candidates"]
            after = row["candidates"]
            if routed_field in row:
                if not row[routed_field] and before != after:
                    raise ValueError(f"Unexpected ranking change for {key}")
                routed_count += row[routed_field]
            changed_rankings += before != after
            if previous:
                gained = set(after) - set(before)
                displaced = set(before) - set(after)
                if (
                    row.get("policy") != policy
                    or len(gained) > 8
                    or not all(turns[k]["role"] == "user" for k in gained)
                    or not all(turns[k]["role"] == "assistant" for k in displaced)
                    or row["session-seeds"] != previous[key]["session-seeds"]
                ):
                    raise ValueError(f"Invalid candidate supplement for {key}")
            if policy == "session-aggregate":
                head_sessions = {
                    turns[k]["session"] for k in before[: len(before) // 2]
                }
                openers = {}
                for turn in turns.values():
                    if turn["role"] == "user" and turn["session"] in head_sessions:
                        openers.setdefault(turn["session"], turn["id"])
                retained_sessions = {
                    turns[k]["session"]
                    for k in set(before) & set(after)
                    if turns[k]["role"] == "user"
                }
                if not gained <= set(openers.values()) or any(
                    turns[k]["session"] not in retained_sessions for k in displaced
                ):
                    raise ValueError(
                        f"Invalid opening turn or displaced session for {key}"
                    )
            if (
                len(before) != len(after)
                or len(after) != len(set(after))
                or len(after) > 80
                or not set(after) <= turns.keys()
                or after[: len(before) // 2] != before[: len(before) // 2]
            ):
                raise ValueError(f"Invalid ranking or protected prefix for {key}")
            first = {}
            for hit in old["ontology-flat"]:
                first.setdefault(turns[hit]["session"], hit)
            if list(first.values())[:8] != row["session-seeds"]:
                raise ValueError(f"Wrong seed order for {key}")
            cases[key] = hashlib.sha256(json.dumps(after).encode()).hexdigest()
            gold = read(folder / "gold.json")
            if gold["abstention"]:
                continue
            annotated = set(gold["annotated"])
            if not annotated or not annotated <= turns.keys():
                raise ValueError(f"Invalid annotations for {key}")
            original = annotated.intersection(before)
            revised = annotated.intersection(after)
            for name, hits in (("original", original), ("revised", revised)):
                counts = summary[name]
                counts["answerable"] += 1
                counts["any"] += bool(hits)
                counts["complete"] += hits == annotated
                counts["turns"] += len(hits)
            if original != revised:
                changes.append(
                    {
                        "id": key,
                        "category": gold["category"],
                        "required_turns": len(annotated),
                        "before": len(original),
                        "after": len(revised),
                        "gained_turns": sorted(revised - original),
                        "lost_turns": sorted(original - revised),
                    }
                )
    if cases.keys() != expected or len(expected) != len(manifest["ids"]):
        raise ValueError("Replay does not cover the original manifest")
    return {
        "dataset_sha256": manifest["dataset_sha256"],
        "policy": policy,
        "baseline": {
            "session-only": "distinct-sessions-v1",
            "session-bm25": "session-bm25",
            "session-date": "session-date",
            None: "original",
        }[baseline_kind],
        "changed_rankings": changed_rankings,
        **(
            {routed_field.replace("-", "_"): routed_count}
            if routed_field and routed_count
            else {}
        ),
        "cutoff": 80,
        "cases_verified": len(cases),
        "coverage": summary,
        "changed_evidence": changes,
        "ranking_set_sha256": hashlib.sha256(
            json.dumps(cases, sort_keys=True).encode()
        ).hexdigest(),
    }


if __name__ == "__main__":
    p = argparse.ArgumentParser()
    for field in ("root", "replay", "output"):
        p.add_argument(field, type=Path)
    p.add_argument("--baseline-replay", type=Path)
    a = p.parse_args()
    report = compare(a.root, a.replay, a.baseline_replay)
    a.output.write_text(json.dumps(report, indent=2) + "\n")
    print(report["coverage"])
