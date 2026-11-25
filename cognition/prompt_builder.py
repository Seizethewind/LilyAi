"""Build the text prompt sent to the model.

Purpose: combine identity, recalled context and chat history.
Depends on: conversation.message.
"""
from conversation.message import Message


def build_prompt(system_prompt: str, context: list[str], messages: list[Message]) -> str:
    raise NotImplementedError
