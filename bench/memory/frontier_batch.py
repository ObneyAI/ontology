"""Submit content-addressed OpenRouter batches and retain per-request responses."""

import hashlib
import json
import os
import time
from urllib.error import HTTPError
from urllib.request import Request, urlopen

from compare import read
from full_run import write

LUNA = "openai/gpt-6-luna"
SONNET = "anthropic/claude-sonnet-5.5"


def api(path, body=None):
    req = Request(
        "https://openrouter.ai/api/v1/" + path,
        data=json.dumps(body).encode() if body is not None else None,
        headers={
            "Authorization": "Bearer " + os.environ["OPENROUTER_API_KEY"],
            "Content-Type": "application/json",
        },
    )
    try:
        with urlopen(req, timeout=150) as response:
            return json.load(response)
    except HTTPError as err:
        raise RuntimeError(
            f"OpenRouter {err.code}: {err.read().decode()[:700]}"
        ) from err


def batch(directory, name, model, bodies):
    request = {
        "endpoint": "/v1/chat/completions",
        "model": model,
        "requests": [{"custom_id": k, "body": v} for k, v in sorted(bodies.items())],
    }
    signature = hashlib.sha256(
        json.dumps(request, sort_keys=True).encode()
    ).hexdigest()[:16]
    prefix = directory / f"{name}-{signature}"
    ticket = prefix.with_suffix(".ticket.json")
    output = prefix.with_suffix(".results.json")
    if output.exists():
        return read(output)
    if not ticket.exists():
        created = api("batches", request)
        write(ticket, created)
    created = read(ticket)
    bid = created["id"]
    print("monitor", name, bid, len(bodies), flush=True)
    while True:
        try:
            status = api("batches/" + bid)
        except (OSError, RuntimeError) as exc:
            print("poll", str(exc)[:120], flush=True)
            time.sleep(30)
            continue
        if status["status"] in {"completed", "failed", "expired", "cancelled"}:
            write(output, status)
            return status
        time.sleep(20)
