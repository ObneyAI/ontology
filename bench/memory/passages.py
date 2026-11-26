"""Rerank graph hits at passage granularity, then present dated evidence in order."""

import hashlib
import os
from functools import lru_cache
from pathlib import Path

import numpy as np
import tiktoken

WINDOW = 128
STRIDE = 96


@lru_cache(maxsize=1)
def load_reranker():
    import onnxruntime as ort
    from tokenizers import Tokenizer

    directory = Path(os.getenv("PILOT_RERANKER", "target/longmem/reranker"))
    for name, expected in {
        "model.onnx": "3573b6b9593cb2f75987a31815d409ca3dd8808629118fd20451bb1a5d90cec7",
        "tokenizer.json": "d241a60d5e8f04cc1b2b3e9ef7a4921b27bf526d9f6050ab90f9267a1f9e5c66",
    }.items():
        with (directory / name).open("rb") as stream:
            if hashlib.file_digest(stream, "sha256").hexdigest() != expected:
                raise ValueError(f"Unexpected reranker file: {name}")
    tokenizer = Tokenizer.from_file(str(directory / "tokenizer.json"))
    tokenizer.enable_truncation(max_length=256)
    tokenizer.enable_padding()
    options = ort.SessionOptions()
    options.intra_op_num_threads = 4
    return tokenizer, ort.InferenceSession(
        str(directory / "model.onnx"), options, providers=["CPUExecutionProvider"]
    )


def score_passages(case, ranking, question):
    codec = tiktoken.get_encoding("cl100k_base")
    turns = {turn["id"]: turn for turn in case["turns"]}
    passages = []
    for key in ranking:
        tokens = codec.encode_ordinary(turns[key]["text"])
        for start in range(0, len(tokens), STRIDE):
            text = codec.decode(tokens[start : start + WINDOW])
            passages.append((key, start, text))
            if start + WINDOW >= len(tokens):
                break

    tokenizer, model = load_reranker()
    scores = []
    for start in range(0, len(passages), 16):
        batch = passages[start : start + 16]
        pairs = [(question, text) for _, _, text in batch]
        encoded = tokenizer.encode_batch(pairs)
        inputs = {
            name: np.asarray([getattr(item, field) for item in encoded], dtype=np.int64)
            for name, field in [
                ("input_ids", "ids"),
                ("attention_mask", "attention_mask"),
                ("token_type_ids", "type_ids"),
            ]
        }
        scores.extend(model.run(None, inputs)[0].ravel().tolist())

    return [passages[i] for i in sorted(range(len(scores)), key=lambda i: -scores[i])]


def pack(case, ranked, budget, selected=None):
    codec = tiktoken.get_encoding("cl100k_base")
    turns = {turn["id"]: turn for turn in case["turns"]}
    # Keep the best window per turn. A single verbose message must not fill the budget.
    selected = dict(selected or {})
    for key, offset, text in ranked:
        if key in selected:
            continue
        turn = turns[key]
        block = f"[{key} {turn['date']} {turn['role']}] {text}"
        trial = {**selected, key: (offset, block)}
        rendered = "\n".join(trial[k][1] for k in sorted(trial))
        if len(codec.encode_ordinary(rendered)) <= budget:
            selected = trial
    return selected


def render(selected):
    codec = tiktoken.get_encoding("cl100k_base")
    keys = sorted(selected)
    prompt = "\n".join(selected[k][1] for k in keys)
    return (
        prompt,
        keys,
        len(codec.encode_ordinary(prompt)),
        {k: selected[k][0] for k in keys},
    )


def context(case, ranking, budget):
    return render(pack(case, score_passages(case, ranking, case["question"]), budget))
