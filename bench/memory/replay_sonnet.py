"""Replay the saved 21-question evidence panel through the hosted Sonnet reader."""

import argparse
import hashlib
import json
import os
from concurrent.futures import ThreadPoolExecutor
from pathlib import Path
from urllib.error import HTTPError
from urllib.request import Request, urlopen

import tiktoken
from audit_followup import EVALUATOR_SHA, judge_answer, load_prompt_builder
from compare import read
from pilot import BUDGET, DATA_SHA, READER, api, evidence_reader_prompt, save

from evidence import render

MODEL = "anthropic/claude-sonnet-5.5"


def hosted_answer(body):
    request = Request(
        "https://openrouter.ai/api/v1/chat/completions",
        data=json.dumps(body).encode(),
        headers={
            "Authorization": "Bearer " + os.environ["OPENROUTER_API_KEY"],
            "Content-Type": "application/json",
        },
    )
    try:
        with urlopen(request, timeout=180) as response:
            result = json.load(response)
    except HTTPError as error:
        raise RuntimeError(
            f"OpenRouter {error.code}: {error.read().decode()[:800]}"
        ) from error
    if result.get("error") or not result.get("choices"):
        raise ValueError(f"OpenRouter returned an error: {result.get('error')}")
    choice = result["choices"][0]
    if result["model"] != MODEL or choice["finish_reason"] not in {"length", "stop"}:
        raise ValueError(
            f"Unexpected model or completion status: {result['model']}, {choice['finish_reason']}"
        )
    answer = (choice["message"].get("content") or "").strip()
    return {
        "answer": answer,
        "provider": result["provider"],
        "generation_id": result["id"],
        "model": result["model"],
        "finish_reason": choice["finish_reason"],
        "usage": result["usage"],
    }


def cached_answer(case_id, case, context, mode, cache):
    system, payload, limit = evidence_reader_prompt(case, context, mode)
    messages = [
        {"role": "system", "content": system},
        {"role": "user", "content": json.dumps(payload)},
    ]
    body = {
        "model": MODEL,
        "messages": messages,
        "reasoning": {"effort": "low", "exclude": True},
        "max_tokens": 1024,
    }
    key = hashlib.sha256(json.dumps(body, sort_keys=True).encode()).hexdigest()
    file = cache / f"{case_id}-{key}.json"
    if file.exists():
        result = read(file)
    else:
        result = hosted_answer(body)
        temp = file.with_suffix(".tmp")
        save(temp, result)
        temp.replace(file)
    return result, key, limit


def replay(run, baseline_file, evaluator, cache):
    baseline = read(baseline_file)
    rubric = load_prompt_builder(evaluator)
    digest = next(m["digest"] for m in api("tags")["models"] if m["name"] == READER)
    if (
        digest != baseline["models"][READER]
        or baseline["rubric_sha256"] != EVALUATOR_SHA
        or baseline["dataset_sha256"] != DATA_SHA
    ):
        raise ValueError("Local judge, evaluator or dataset differs from the baseline")
    samples = baseline["cases"]
    if len(samples) != 21 or len({s["id"] for s in samples}) != 21:
        raise ValueError("Expected the 21 paired held-out questions")
    codec = tiktoken.get_encoding("cl100k_base")
    cache.mkdir(parents=True, exist_ok=True)

    def answer(sample):
        source = sample["results"]["ontology-evidence"]
        case = read(run / sample["id"] / "input.json")
        context = render(case, source["records"])
        tokens = len(codec.encode_ordinary(context))
        if (
            tokens != source["context_tokens"]
            or tokens > BUDGET
            or sample["question"] != case["question"]
        ):
            raise ValueError(f"Evidence mismatch for {sample['id']}")
        result, key, limit = cached_answer(
            sample["id"], case, context, source["mode"], cache
        )
        visible = codec.encode_ordinary(result["answer"])
        judged = (
            codec.decode(visible[:limit]) if len(visible) > limit else result["answer"]
        )
        return {
            **result,
            "id": sample["id"],
            "category": sample["type"],
            "context_tokens": tokens,
            "request_sha256": key,
            "visible_tokens": len(visible),
            "judged_answer": judged,
            "gemma_correct": source["task_rubric_correct"],
        }

    def grade(sample, response):
        baseline_row = sample["results"]["ontology-evidence"]
        return bool(response) and judge_answer(
            rubric(
                baseline_row["category"],
                sample["question"],
                sample["reference"],
                response,
                abstention=baseline_row["abstention"],
            )
        )

    rows = []
    with ThreadPoolExecutor(max_workers=3) as pool:
        for s, r in zip(samples, pool.map(answer, samples), strict=True):
            r["sonnet_correct"] = grade(s, r["judged_answer"])
            r["full_answer_correct"] = (
                r["sonnet_correct"]
                if r["answer"] == r["judged_answer"]
                else grade(s, r["answer"])
            )
            rows.append(r)
            print(r["id"], r["sonnet_correct"], flush=True)
    return {
        "reader": MODEL,
        "reasoning_effort": "low",
        "max_tokens": 1024,
        "visible_token_cap": {"encoding": "cl100k_base", "tokens": 160},
        "judge": {READER: digest},
        "rubric_sha256": EVALUATOR_SHA,
        "baseline_sha256": hashlib.sha256(baseline_file.read_bytes()).hexdigest(),
        "gemma_correct": sum(r["gemma_correct"] for r in rows),
        "sonnet_correct": sum(r["sonnet_correct"] for r in rows),
        "full_answer_correct": sum(r["full_answer_correct"] for r in rows),
        "cost_usd": round(sum(r["usage"]["cost"] for r in rows), 8),
        "cases": rows,
    }


if __name__ == "__main__":
    parser = argparse.ArgumentParser()
    for arg in ("run", "baseline", "evaluator", "cache", "output"):
        parser.add_argument(arg, type=Path)
    args = parser.parse_args()
    save(args.output, replay(args.run, args.baseline, args.evaluator, args.cache))
