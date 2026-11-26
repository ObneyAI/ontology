"""Run a budget reader and competitor-matched reader on identical evidence in batches."""

import argparse
import hashlib
import json
import time
from concurrent.futures import ThreadPoolExecutor
from datetime import UTC, datetime
from pathlib import Path

import tiktoken
from audit_followup import load_prompt_builder
from compare import read
from frontier_batch import LUNA, SONNET, api, batch
from full_run import write
from pilot import BUDGET, evidence_reader_prompt

from evidence import KINDS, MODES, explicit_mode, select_records, selector_request

READERS = {
    LUNA: "luna",
    SONNET: "sonnet",
    "deepseek/deepseek-v4.1-flash-1017": "deepseek",
}


def selection_json(text, question):
    # Some hosted responses append a repeated object or prose after the structured response.
    # Accept only the first complete selection and pass source IDs through the normal span validation.
    try:
        obj, end = json.JSONDecoder().raw_decode(text.lstrip())
    except ValueError:
        obj, end = {}, 0
    if not isinstance(obj, dict):
        obj = {}
    groups = obj.get("records")
    records = (
        [
            g
            for g in groups
            if isinstance(g, dict)
            and g.get("kind") in KINDS
            and isinstance(g.get("ids"), list)
        ][:10]
        if isinstance(groups, list)
        else []
    )
    mode = explicit_mode(question) or obj.get("mode")
    if mode not in MODES:
        mode = "recall"
    flags = {
        "trailing_output": bool(text.lstrip()[end:].strip()),
        "empty_selection": not records,
    }
    return json.dumps({"mode": mode, "records": records}), flags


def choices(result):
    if result["status"] != "completed":
        raise ValueError(
            f"Batch {result['id']} ended with {result['status']}: {result.get('error')}"
        )
    raw = result["results"]
    rows = {}
    for row in raw:
        response = row.get("response") or {}
        data = response.get("body", response)
        if "choices" not in data and "choices" in row:
            data = row
        if not data.get("choices"):
            raise ValueError(
                f"Unsuccessful batch row {row.get('custom_id')} in {result['id']}: {str(row)[:350]}"
            )
        c = data["choices"][0]
        if c["finish_reason"] not in {"length", "stop", "content_filter"}:
            raise ValueError(f"Reader error: {c['finish_reason']}")
        rows[row["custom_id"]] = {
            "answer": (c["message"].get("content") or "").strip(),
            "usage": data.get("usage", {}),
            "model": data.get("model") or result["model"],
            "batch": result["id"],
            "finish_reason": c["finish_reason"],
        }
    return rows


def get_pool(folder, archived):
    row = read(folder / "pool.json")
    if not row.get("archived"):
        return row
    source = archived[folder.name]
    turns = {t["id"]: t for t in read(folder / "input.json")["turns"]}
    text = ""
    for i, (key, l, r) in enumerate(source["pool_sources"]):
        t = turns[key]
        text += f"[{i} {t['date']} {t['role']}] {t['text'][l:r]}\n"
    return {"spans": source["pool_sources"], "text": text}


def call_group(root, out, name, keys, archive, submit=batch):
    codec = tiktoken.get_encoding("cl100k_base")
    rubric = load_prompt_builder(root.parent / "upstream_evaluate_qa.py")
    while any(not (root / k / "pool.json").exists() for k in keys):
        time.sleep(5)
    pools = {k: get_pool(root / k, archive) for k in keys}
    selectors = {}
    cases = {}
    for k in keys:
        case = read(root / k / "input.json")
        cases[k] = {
            **{field: case[field] for field in ("question", "date")},
            "turns": [
                {f: v for f, v in t.items() if f != "vector"} for t in case["turns"]
            ],
        }
        pool = pools[k]
        if not pool["spans"]:
            continue
        req = selector_request(case, pool["spans"], pool["text"], LUNA)
        schema = req["format"]
        schema["additionalProperties"] = False
        schema["properties"]["records"]["items"]["additionalProperties"] = False
        selectors[k] = {
            "messages": req["messages"],
            "response_format": {
                "type": "json_schema",
                "json_schema": {"name": "evidence", "strict": True, "schema": schema},
            },
            "reasoning": {"effort": "none"},
            "max_tokens": 650,
        }
    selected = choices(submit(out, name + "-select", LUNA, selectors))
    if set(selected) != set(selectors):
        raise ValueError("Selector batch IDs do not match")
    reader = {}
    evidence = {}
    for k in keys:
        resp = selected.get(
            k,
            {
                "answer": "",
                "usage": {"prompt_tokens": 0, "completion_tokens": 0, "cost": 0},
                "batch": None,
            },
        )
        if resp.get("finish_reason") == "content_filter":
            pools[k] = {"spans": [], "text": ""}
        structured, flags = selection_json(resp["answer"], cases[k]["question"])
        ollama = {
            "message": {"content": structured},
            "prompt_eval_count": resp["usage"]["prompt_tokens"],
            "eval_count": resp["usage"]["completion_tokens"],
        }
        prompt, _, count, detail = select_records(
            cases[k],
            pools[k]["spans"],
            pools[k]["text"],
            codec,
            BUDGET,
            lambda *args, response=ollama, **kwargs: response,
            LUNA,
        )
        system, payload, _ = evidence_reader_prompt(cases[k], prompt, detail["mode"])
        reader[k] = {
            "messages": [
                {"role": "system", "content": system},
                {"role": "user", "content": json.dumps(payload)},
            ],
            "reasoning": {"effort": "low", "exclude": True},
            "max_tokens": 1024,
        }
        evidence[k] = {
            "records": detail["records"],
            "mode": detail["mode"],
            "context_tokens": count,
            "context_sha256": hashlib.sha256(prompt.encode()).hexdigest(),
            "selector_usage": resp["usage"],
            "selector_batch": resp["batch"],
            "parse_diagnostics": flags,
            "selection_unavailable": resp.get("finish_reason") == "content_filter",
        }
    with ThreadPoolExecutor(max_workers=len(READERS)) as pool:
        futures = {
            m: pool.submit(submit, out, name + "-" + m.split("/")[-1], m, reader)
            for m in READERS
        }
        answers = {m: choices(f.result()) for m, f in futures.items()}
    judges = {}
    for k in keys:
        gold = read(root / k / "gold.json")
        for model, label in READERS.items():
            answer = answers[model][k]["answer"]
            visible = codec.encode_ordinary(answer)
            candidates = {"full": answer}
            if len(visible) > 160:
                candidates["limited"] = codec.decode(visible[:160])
            for method, candidate in candidates.items():
                judges[f"{k}_{label}_{method}"] = {
                    "messages": [
                        {
                            "role": "user",
                            "content": rubric(
                                gold["category"],
                                cases[k]["question"],
                                gold["reference"],
                                candidate,
                                abstention=gold["abstention"],
                            ),
                        }
                    ],
                    "reasoning": {"effort": "none"},
                    "max_tokens": 20,
                }
    scores = choices(submit(out, name + "-judge", LUNA, judges))
    if set(scores) != set(judges):
        raise ValueError("Judge batch IDs do not match")
    for k in keys:
        row = {"id": k, "evidence": evidence[k]}
        for model, label in READERS.items():
            prefix = f"{k}_{label}_"
            full = scores[prefix + "full"]
            limited = scores.get(prefix + "limited", full)
            row[label] = {
                **answers[model][k],
                "correct": verdict(full),
                "limited": verdict(limited),
            }
        write(out / (k + ".json"), row)


def verdict(row):
    val = row["answer"].strip().lower().rstrip(".!")
    if val not in ("yes", "no"):
        raise ValueError(f"Invalid judge verdict: {val[:150]}")
    return val == "yes"


def competitor(root, out, submit=batch):
    rubric = load_prompt_builder(root.parent / "upstream_evaluate_qa.py")
    bodies = {}
    for q in read(root.parent / "dataset.json"):
        k = q["question_id"]
        rival = read(root / "competitor-cache" / (k + ".json"))
        if rival["question"] != q["question"]:
            raise ValueError(f"Competitor question mismatch: {k}")
        prompt = rubric(
            q["question_type"],
            q["question"],
            q["answer"],
            rival["answer"],
            abstention=k.endswith("_abs"),
        )
        bodies[k] = {
            "messages": [{"role": "user", "content": prompt}],
            "reasoning": {"effort": "none"},
            "max_tokens": 20,
        }
    return submit(out, "competitor", LUNA, bodies)


def run(root, out, artifact, submit=batch):
    out.mkdir(exist_ok=True)
    if not (out / "catalog.json").exists():
        model_ids = set(READERS) | {model + ":batch" for model in READERS}
        write(
            out / "catalog.json",
            {
                "as_of": datetime.now(UTC).isoformat(),
                "models": [r for r in api("models")["data"] if r["id"] in model_ids],
            },
        )
    m = read(root / "manifest.json")
    archive = {
        c["id"]: c["results"]["ontology-evidence"]
        for panel in ("heldout", "preferences", "development")
        for c in read(artifact / (panel + ".json"))["cases"]
    }
    keys = sorted(m["ids"], key=lambda k: k not in m["previously_run"])
    with ThreadPoolExecutor(max_workers=6) as pool:
        futures = [
            pool.submit(
                call_group,
                root,
                out,
                f"group{i // 100 + 1}",
                keys[i : i + 100],
                archive,
                submit,
            )
            for i in range(0, len(keys), 100)
        ]
        futures.append(pool.submit(competitor, root, out, submit))
        for f in futures:
            f.result()


if __name__ == "__main__":
    p = argparse.ArgumentParser()
    for arg in ("root", "out", "artifact"):
        p.add_argument(arg, type=Path)
    p.add_argument("--on-demand", action="store_true")
    a = p.parse_args()
    if a.on_demand:
        from frontier_live import live
    run(a.root, a.out, a.artifact, live if a.on_demand else batch)
