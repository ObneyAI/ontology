"""Check raw ontology rankings against source turns and benchmark annotations."""

import argparse
from collections import defaultdict
from pathlib import Path

from compare import read
from full_run import write


def session_links(turns):
    # The importer drops blank messages and links consecutive turns within each session.
    by_session = defaultdict(list)
    for turn in turns.values():
        if turn["text"].strip():
            by_session[turn["session"]].append(turn["id"])
    return {
        key: (session, index, order)
        for session, order in by_session.items()
        for index, key in enumerate(order)
    }


def adjacent_candidates(original, links):
    # Keep the cutoff fixed. Give the last eight places to one-hop neighbors of
    # high-ranked hits, strongest hit first. Fall back to the original ranking.
    keep = max(len(original) - 8, 0)
    neighbors = []
    for key in original[:keep]:
        _, index, order = links[key]
        if index:
            neighbors.append(order[index - 1])
        neighbors.extend(order[index + 1 : index + 2])
    return list(dict.fromkeys([*original[:keep], *neighbors, *original[keep:]]))[
        : len(original)
    ]


def trace_miss(annotated, raw, links):
    def distance(key, other):
        session, pos, _ = links[key]
        return abs(pos - links[other][1]) if session == links[other][0] else None

    traces = []
    for key in sorted(annotated):
        hits = [
            (rank, gap)
            for rank, other in enumerate(raw["candidates"], 1)
            if (gap := distance(key, other)) is not None
        ]
        seed_gaps = [
            gap
            for other in raw["ontology-flat"][:3]
            if (gap := distance(key, other)) is not None
        ]
        traces.append(
            {
                "turn": key,
                "best_same_session_rank": hits[0][0] if hits else None,
                "hops_to_nearest_result": min((gap for _, gap in hits), default=None),
                "hops_to_nearest_original_seed": min(seed_gaps, default=None),
            }
        )
    return traces


def audit(root):
    ids = read(root / "manifest.json")["ids"]
    if len(set(ids)) != len(ids):
        raise ValueError("Duplicate question ID in the manifest")
    rankings = {"ontology-flat": 30, "ontology": 30, "candidates": 80}
    coverage = {name: {"any": 0, "all": 0, "turns_found": 0} for name in rankings}
    misses = []
    complete_miss_traces = []
    trial = {
        "any": 0,
        "all": 0,
        "turns_found": 0,
        "new_any_hits": [],
        "lost_any_hits": [],
        "new_complete": [],
        "lost_complete": [],
    }
    answerable = total_turns = 0
    for key in ids:
        folder = root / key
        turns = {t["id"]: t for t in read(folder / "input.json")["turns"]}
        raw = read(folder / "ontology.json")
        gold = read(folder / "gold.json")
        annotated = set(gold["annotated"])
        if not annotated <= turns.keys() or raw["candidates"][:30] != raw["ontology"]:
            raise ValueError(f"Source mapping or ranking prefix mismatch for {key}")
        for name, limit in rankings.items():
            hits = raw[name]
            if (
                len(hits) > limit
                or len(set(hits)) != len(hits)
                or any(k not in turns or not turns[k]["text"].strip() for k in hits)
            ):
                raise ValueError(f"Invalid {name} rankings for {key}")
        links = session_links(turns)
        candidate = adjacent_candidates(raw["candidates"], links)
        if len(candidate) != len(raw["candidates"]) or len(set(candidate)) != len(
            candidate
        ):
            raise ValueError(f"Counterfactual cutoff changed for {key}")
        if gold["abstention"]:
            continue
        if not annotated:
            raise ValueError(f"No annotated turn for answerable question {key}")
        answerable += 1
        total_turns += len(annotated)
        for name in rankings:
            found = annotated.intersection(raw[name])
            coverage[name]["any"] += bool(found)
            coverage[name]["all"] += found == annotated
            coverage[name]["turns_found"] += len(found)
        original = annotated.intersection(raw["candidates"])
        alternative = annotated.intersection(candidate)
        trial["any"] += bool(alternative)
        trial["all"] += alternative == annotated
        trial["turns_found"] += len(alternative)
        for metric, before, after in [
            ("any_hits", bool(original), bool(alternative)),
            ("complete", original == annotated, alternative == annotated),
        ]:
            if before != after:
                trial[("new_" if after else "lost_") + metric].append(key)
        if not original:
            if not annotated <= links.keys():
                raise ValueError(f"Annotated turn omitted during ingestion for {key}")
            complete_miss_traces.append(
                {
                    "id": key,
                    "category": gold["category"],
                    "turns": trace_miss(annotated, raw, links),
                    "found_by_adjacent_trial": sorted(alternative),
                }
            )
        missing = annotated.difference(raw["candidates"])
        if missing:
            misses.append(
                {
                    "id": key,
                    "category": gold["category"],
                    "total_annotated": len(annotated),
                    "missing_from_top_80": sorted(missing),
                }
            )
    return {
        "rankings_checked": len(ids),
        "answerable_questions": answerable,
        "annotated_question_turn_pairs": total_turns,
        "coverage": coverage,
        "adjacent_top_80_trial": trial,
        "complete_miss_traces": complete_miss_traces,
        "incomplete_top_80": misses,
    }


if __name__ == "__main__":
    parser = argparse.ArgumentParser()
    parser.add_argument("root", type=Path)
    parser.add_argument("output", type=Path)
    args = parser.parse_args()
    write(args.output, audit(args.root))
