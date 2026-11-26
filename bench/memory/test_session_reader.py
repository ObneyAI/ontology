import unittest

import tiktoken
from session_reader import conversation


class SessionReaderTest(unittest.TestCase):
    def test_restores_dated_context_only_from_retrieved_sessions(self):
        def turn(k, role, text):
            return {
                "id": k,
                "session": k[:4],
                "date": "2024/02/29",
                "role": role,
                "text": text,
            }

        case = {
            "turns": [
                turn("s000-000", "user", "An unrelated conversation."),
                turn("s001-000", "user", "I wake up at seven."),
                turn("s001-001", "assistant", "And for early classes?"),
                turn("s001-002", "user", "Fifteen minutes earlier on Tuesdays."),
            ]
        }
        codec = tiktoken.get_encoding("cl100k_base")
        text, spans = conversation(case, [("s001-002", 0, 7)], ["s001-002"], codec, 100)
        self.assertEqual(["s001-000", "s001-002"], [k for k, _, _ in spans])
        self.assertIn("[s001-000 2024/02/29 user] I wake up at seven.", text)
        self.assertLessEqual(len(codec.encode_ordinary(text)), 100)
        self.assertEqual(("", []), conversation(case, [], [], codec))
