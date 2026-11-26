import unittest
from unittest.mock import patch

import tiktoken

import evidence


class EvidenceTest(unittest.TestCase):
    def test_source_identity_and_budget(self):
        case = {
            "date": "2024/03/02 (Sat) 10:00",
            "question": "Any advice for the race?",
            "turns": [
                {
                    "id": "s000-000",
                    "session": "s000",
                    "role": "user",
                    "date": "2024/03/01 (Fri) 09:00",
                    "text": "Yesterday I ran my first race.",
                },
                {
                    "id": "s000-001",
                    "session": "s000",
                    "role": "assistant",
                    "date": "2024/03/01 (Fri) 09:00",
                    "text": "Try a slower pace next time.",
                },
            ],
        }

        def selector(*args, **kwargs):
            return {
                "message": {
                    "content": '{"mode":"recall","records":[{"kind":"event","ids":[0,0,123,-1]}]}'
                },
                "prompt_eval_count": 100,
                "eval_count": 20,
            }

        key = "s000-001"
        # Retrieval hit was an assistant reply; its preceding speaker testimony must remain attributable.
        with patch.object(
            evidence.passages,
            "score_passages",
            return_value=[(key, 0, case["turns"][1]["text"])],
        ):
            prompt, used, size, detail = evidence.context(
                case, [key], 100, selector, "local"
            )
        self.assertEqual(["s000-000"], used)
        self.assertEqual("advice", detail["mode"])
        self.assertEqual(
            [["s000-000", 0, 30]], [list(s) for s in detail["records"][0]["sources"]]
        )
        self.assertIn("yesterday = 2024-02-29", prompt)
        self.assertEqual(
            size, len(tiktoken.get_encoding("cl100k_base").encode_ordinary(prompt))
        )
        self.assertLessEqual(size, 100)

    def test_question_only_routing(self):
        for question, expected in [
            ("Any advice on preparing for a race?", "advice"),
            ("How many races did I run?", "aggregation"),
            ("Which race came first?", "timeline"),
            ("What was the name of the race?", None),
        ]:
            with self.subTest(question=question):
                self.assertEqual(expected, evidence.requested_task(question))
                self.assertEqual(expected is not None, evidence.use_evidence(question))

    def test_numbered_item_and_relative_date(self):
        codec = tiktoken.get_encoding("cl100k_base")
        text = "\n".join(
            f"{i}. Complete entry with label and value." for i in range(1, 101)
        )
        pieces = [text[a:b] for a, b in evidence.spans(text, codec)]
        self.assertIn("27. Complete entry with label and value.", pieces)
        self.assertEqual(
            "two weeks ago = 2023-12-25",
            evidence.relative_dates("2024/01/08", "I went two weeks ago."),
        )
        self.assertEqual("", evidence.relative_dates("2024/01/08", "I went recently."))
