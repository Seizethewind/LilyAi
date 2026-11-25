"""Ordered message history."""
from conversation.message import Message


class Conversation:
    def __init__(self) -> None:
        self.messages: list[Message] = []

    def add(self, role: str, content: str) -> Message:
        msg = Message(role=role, content=content)
        self.messages.append(msg)
        return msg
