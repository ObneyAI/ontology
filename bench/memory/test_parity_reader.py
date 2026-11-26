import unittest

import tiktoken
from parity_reader import LIMIT, pack, task_for
from parity_review import source_groups


class ParityReaderTest(unittest.TestCase):
    def test_historical_recommendation_and_new_request_take_different_routes(self):
        self.assertEqual(task_for("What did you recommend for my trip?"), "recall")
        self.assertEqual(task_for("Can you recommend a book for my trip?"), "advice")
        self.assertEqual(task_for("How many books did I take?"), "synthesis")

    def test_source_groups_and_context_only_expand_retrieved_sessions(self):
        turns = [
            {
                "id": k,
                "session": k[:4],
                "date": "2024/04/20",
                "role": role,
                "text": text,
            }
            for k, role, text in [
                ("s000-000", "user", "I like this unrelated thing."),
                ("s001-000", "user", "I own three mystery novels."),
                ("s001-001", "assistant", "Would you like another?"),
                ("s001-002", "user", "I bought another one today."),
            ]
        ]
        codec = tiktoken.get_encoding("cl100k_base")
        case = {"turns": turns}
        hits = ["s001-002"]
        context, spans = pack(case, [], hits, codec)
        selected = {k for k, _, _ in spans}
        self.assertEqual(selected, {"s001-000", "s001-002"})
        self.assertEqual(
            {k for group in source_groups(case, hits, codec) for k, _ in group},
            selected,
        )
        self.assertLessEqual(len(codec.encode_ordinary(context)), LIMIT)
        self.assertEqual(pack(case, [], [], codec), ("", []))
