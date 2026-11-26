"""Run OpenRouter requests on demand with a per-question restart checkpoint."""

import hashlib
import json
import os
import threading
import time
from concurrent.futures import ThreadPoolExecutor, as_completed

import requests
from compare import read
from frontier_batch import SONNET, api
from full_run import write

POOL = ThreadPoolExecutor(max_workers=48)
LOCAL = threading.local()


def digest(obj):
    return hashlib.sha256(json.dumps(obj, sort_keys=True).encode()).hexdigest()


def filtered(model, reason):
    return {
        "model": model,
        "choices": [{"message": {"content": ""}, "finish_reason": "content_filter"}],
        "usage": {"prompt_tokens": 0, "completion_tokens": 0, "cost": 0},
        "omission_reason": reason,
    }


def completion(cache, key, body):
    signature = digest(body)
    path = cache / f"{key}-{signature}.json"
    if path.exists():
        return read(path)
    prior = cache.parent.parent / "sonnet-5.5-replay" / path.name
    if body["model"] == SONNET and prior.exists():
        previous = read(prior)
        data = {
            "id": previous["generation_id"],
            "provider": previous["provider"],
            "model": previous["model"],
            "choices": [
                {
                    "message": {"content": previous["answer"]},
                    "finish_reason": previous["finish_reason"],
                }
            ],
            "usage": previous["usage"],
            "reused_prior": True,
        }
        write(path, data)
        return data
    if not hasattr(LOCAL, "session"):
        LOCAL.session = requests.Session()
        LOCAL.session.headers["Authorization"] = (
            "Bearer " + os.environ["OPENROUTER_API_KEY"]
        )
    for attempt in range(7):
        try:
            response = LOCAL.session.post(
                "https://openrouter.ai/api/v1/chat/completions",
                json=body,
                timeout=(15, 180),
            )
            data = response.json()
            error = data.get("error") or {}
            code = int(error.get("code") or response.status_code)
            if code in {400, 403} and any(
                word in str(error).lower()
                for word in ("flagged", "moderation", "content filter")
            ):
                data = filtered(body["model"], "provider content filter")
                write(path, data)
                return data
            if code >= 400 or not data.get("choices"):
                if code not in {408, 429, 500, 502, 503, 504, 529}:
                    raise ValueError(
                        f"{body['model']} {key} HTTP {code}: {str(error.get('message'))[:220]}"
                    )
                raise requests.ConnectionError(f"provider HTTP {code}")
            if data["choices"][0].get("finish_reason") == "error":
                raise requests.ConnectionError("upstream inference error")
            if not data.get("usage") or data["usage"].get("cost") is None:
                raise ValueError(f"Missing usage for {key}")
            write(path, data)
            return data
        except (requests.RequestException, requests.exceptions.JSONDecodeError):
            if attempt == 6:
                raise
            time.sleep(min(2**attempt, 25))
    raise RuntimeError("No response")


def live(directory, name, model, bodies):
    request = {
        "endpoint": "/v1/chat/completions",
        "model": model,
        "requests": [{"custom_id": k, "body": v} for k, v in sorted(bodies.items())],
    }
    stem = f"{name}-{digest(request)[:16]}"
    receipt = directory / f"{stem}.live.json"
    if receipt.exists():
        return read(receipt)
    cache = directory / "on-demand"
    cache.mkdir(exist_ok=True)
    saved = directory / f"{stem}.results.json"
    ticket = directory / f"{stem}.ticket.json"
    if ticket.exists():
        old = read(saved) if saved.exists() else api("batches/" + read(ticket)["id"])
        if old["status"] == "completed" and len(old.get("results") or []) == len(
            bodies
        ):
            write(saved, old)
            print("reusing batch", name, len(bodies), flush=True)
            return old
    print("on demand", name, len(bodies), flush=True)
    pending = {
        POOL.submit(completion, cache, k, {"model": model, **b}): k
        for k, b in bodies.items()
    }
    rows = []
    cost = 0
    for future in as_completed(pending):
        data = future.result()
        rows.append({"custom_id": pending[future], "response": {"body": data}})
        if not data.get("reused_prior"):
            cost += data["usage"]["cost"]
    result = {
        "id": stem,
        "status": "completed",
        "model": model,
        "provider": "OpenRouter on demand",
        "request_counts": {"total": len(rows), "completed": len(rows)},
        "usage": {"cost": cost},
        "results": rows,
    }
    write(receipt, result)
    print("completed", name, len(rows), round(cost, 5), flush=True)
    return result
