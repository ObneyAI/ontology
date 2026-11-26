"""Finish unprepared histories using hosted MiniLM embeddings and passage reranking."""

import argparse
import tempfile
import time
from concurrent.futures import ThreadPoolExecutor
from pathlib import Path

import tiktoken
from compare import read
from frontier_batch import api
from full_run import write
from passages import STRIDE, WINDOW
from pilot import prepare_question

from evidence import build_pool

EMBED = "sentence-transformers/all-minilm-l6-v2"
RERANK = "nvidia/nemotron-3.5-rerank-8b:free"


def request(path, body):
    for attempt in range(5):
        try:
            return api(path, body)
        except (RuntimeError, OSError) as exc:
            if "OpenRouter 422" in str(exc) or attempt == 4:
                raise
            print(path, "retry", str(exc)[:120], flush=True)
            time.sleep(3 * (attempt + 1))


def prepare(root):
    rows = read(root.parent / "dataset.json")

    def one(r):
        k = r["question_id"]
        if (root / k / "input.json").exists():
            return
        usages = []

        def embed(inputs):
            actual = [v for v in inputs if v.strip()]
            if not actual:
                return [[0.0] * 384 for _ in inputs]
            result = request(
                "embeddings",
                {"model": EMBED, "input": actual, "encoding_format": "float"},
            )
            returned = sorted(result["data"], key=lambda v: v["index"])
            if [v["index"] for v in returned] != list(range(len(actual))) or any(
                len(v["embedding"]) != 384 for v in returned
            ):
                raise ValueError("Unexpected embedding shape")
            usages.append(result["usage"])
            vectors = iter(v["embedding"] for v in returned)
            return [next(vectors) if v.strip() else [0.0] * 384 for v in inputs]

        with tempfile.TemporaryDirectory(dir=root) as tmp:
            stage = Path(tmp) / k
            stage.mkdir()
            prepare_question(r, stage, embed, batch_size=96)
            write(stage / "hosted-embedding.json", {"model": EMBED, "usage": usages})
            stage.rename(root / k)

    with ThreadPoolExecutor(max_workers=2) as pool:
        list(pool.map(one, rows))


def rerank(root):
    codec = tiktoken.get_encoding("cl100k_base")
    pending = read(root / "manifest.json")["ids"]
    while pending:
        left = []
        for k in pending:
            folder = root / k
            if (folder / "pool.json").exists():
                continue
            if not (folder / "ontology.json").exists():
                left.append(k)
                continue
            case = read(folder / "input.json")
            turns = {t["id"]: t for t in case["turns"]}
            windows = []
            for key in read(folder / "ontology.json")["candidates"]:
                tokens = codec.encode_ordinary(turns[key]["text"])
                for start in range(0, len(tokens), STRIDE):
                    windows.append(
                        (key, start, codec.decode(tokens[start : start + WINDOW]))
                    )
                    if start + WINDOW >= len(tokens):
                        break
            # Score all windows against one query. Preserve the input order on ties.
            scores = []
            usages = []
            for i in range(0, len(windows), 96):
                part = windows[i : i + 96]
                try:
                    response = request(
                        "rerank",
                        {
                            "model": RERANK,
                            "query": case["question"],
                            "documents": [w[2] for w in part],
                            "top_n": len(part),
                        },
                    )
                except RuntimeError as exc:
                    if "content safety screening" not in str(exc):
                        raise
                    print("provider rejected reranking for", k, flush=True)
                    write(
                        folder / "pool.json",
                        {
                            "spans": [],
                            "text": "",
                            "reranker": RERANK,
                            "usage": usages,
                            "provider_blocked": True,
                        },
                    )
                    break
                ordered = sorted(response["results"], key=lambda x: x["index"])
                if [v["index"] for v in ordered] != list(range(len(part))):
                    raise ValueError("Reranker omitted a passage")
                scores.extend(float(v["relevance_score"]) for v in ordered)
                usages.append(response["usage"])
            if (folder / "pool.json").exists():
                continue
            ranked = [
                windows[i]
                for i in sorted(range(len(windows)), key=lambda i: -scores[i])
            ]
            spans, text = build_pool(case, ranked, codec)
            write(
                folder / "pool.json",
                {"spans": spans, "text": text, "reranker": RERANK, "usage": usages},
            )
            print("hosted-rerank", k, flush=True)
        pending = left
        if left:
            time.sleep(3)


if __name__ == "__main__":
    p = argparse.ArgumentParser()
    p.add_argument("stage", choices=("prepare", "rerank"))
    p.add_argument("root", type=Path)
    a = p.parse_args()
    (prepare if a.stage == "prepare" else rerank)(a.root)
