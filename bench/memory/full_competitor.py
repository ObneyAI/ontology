"""Fetch the public Sonnet outputs, indexed by the original benchmark ID."""

import argparse
import json
import time
from concurrent.futures import ThreadPoolExecutor
from pathlib import Path
from urllib.request import urlopen

from compare import read
from full_run import write

BASE = "https://supermemory.ai/research/runs/data/longmemeval/"


def collect(root):
    manifest = read(root / "manifest.json")
    # Store downloads independently of local input preparation.
    cache = root / "competitor-cache"
    cache.mkdir(exist_ok=True)

    def fetch(key):
        path = cache / f"{key}.json"
        if not path.exists():
            for attempt in range(3):
                try:
                    with urlopen(BASE + key + ".json", timeout=60) as response:
                        data = json.load(response)
                    break
                except OSError:
                    if attempt == 2:
                        raise
                    time.sleep(2**attempt)
            reader = data["readers"]["sonnet"]
            if data["id"] != key or reader["model"] != "claude-sonnet-5-5":
                raise ValueError(f"Unmatched upstream row: {key}")
            write(
                path,
                {
                    "id": key,
                    "question": data["question"],
                    "reference": data["reference"]["answer"],
                    "answer": reader["answer"],
                    "judges": reader["judges"],
                    "source": BASE + key + ".json",
                },
            )

    with ThreadPoolExecutor(max_workers=5) as pool:
        list(pool.map(fetch, manifest["ids"]))
    pending = list(manifest["ids"])
    while pending:
        remaining = []
        for key in pending:
            folder = root / key
            if not (folder / "gold.json").exists():
                remaining.append(key)
                continue
            row = read(cache / f"{key}.json")
            if row["question"] != read(folder / "input.json")["question"]:
                raise ValueError(f"Question mismatch: {key}")
            write(folder / "supermemory.json", row)
        pending = remaining
        if pending:
            time.sleep(2)


if __name__ == "__main__":
    parser = argparse.ArgumentParser()
    parser.add_argument("root", type=Path)
    collect(parser.parse_args().root)
