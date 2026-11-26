"""Preserve list labels and explicit ordinal references when quoting a turn."""

import re
from bisect import bisect_right

import tiktoken
from passages import WINDOW

ITEM = re.compile(r"(?m)^[ \t]*(?:(\d+)[.)]|[-*•])\s+")
ORDINAL = re.compile(
    r"\b(\d+)(?:st|nd|rd|th)\s+(?:(?:item|entry|parameter|step|option|point|recommendation)\b"
    r"|(?:on|in|from)\s+(?:(?:the|that|your|my)\s+)?list\b)",
    re.IGNORECASE,
)


def start_for_quote(text, offsets, start, ordinal):
    items = list(ITEM.finditer(text))
    if ordinal is not None:
        matches = [m for m in items if m[1] == ordinal]
        if len(matches) == 1 and len(items) > 1:
            return bisect_right(offsets, matches[0].start()) - 1
    # Restore the label of the current item only when the original quote starts inside it.
    position = offsets[start]
    for m in reversed(items):
        if m.start() <= position:
            begin = bisect_right(offsets, m.start()) - 1
            if start - begin <= 48 and "\n\n" not in text[m.start() : position]:
                return begin
            break
    return start


def repair(case, initial, budget):
    codec = tiktoken.get_encoding("cl100k_base")
    question = case["question"]
    requested = ORDINAL.findall(question)
    ordinal = requested[0] if len(requested) == 1 else None
    blocks, offsets, lengths = {}, {}, {}
    turns = {t["id"]: t for t in case["turns"]}
    proposals = []
    for key in sorted(initial):
        t = turns[key]
        tokens = codec.encode_ordinary(t["text"])
        start = initial[key]
        size = min(WINDOW, len(tokens) - start)
        prefix = f"[{key} {t['date']} {t['role']}] "
        blocks[key] = prefix + codec.decode(tokens[start : start + size])
        offsets[key], lengths[key] = start, size
        if len(tokens) > WINDOW:
            text, positions = codec.decode_with_offsets(tokens)
            proposed = start_for_quote(text, positions, start, ordinal)
            if proposed != start:
                length = min(size, len(tokens) - proposed)
                proposals.append(
                    (
                        key,
                        proposed,
                        length,
                        prefix + codec.decode(tokens[proposed : proposed + length]),
                    )
                )
    for key, proposed, length, block in proposals:
        trial = {**blocks, key: block}
        if len(codec.encode_ordinary("\n".join(trial.values()))) <= budget:
            blocks, offsets[key], lengths[key] = trial, proposed, length
    prompt = "\n".join(blocks.values())
    return prompt, offsets, lengths, len(codec.encode_ordinary(prompt))
