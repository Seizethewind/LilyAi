"""Trim history to fit a context budget."""
from conversation.message import Message


class ContextManager:
    def __init__(self, max_chars: int = 8000) -> None:
        self.max_chars = max_chars

    def fit(self, messages: list[Message]) -> list[Message]:
        kept: list[Message] = []
        total = 0
        for msg in reversed(messages):
            total += len(msg.content)
            if total > self.max_chars:
                break
            kept.append(msg)
        return list(reversed(kept))
