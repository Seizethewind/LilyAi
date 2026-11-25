"""Parse raw model output into a Decision.

Purpose: separate plain replies from tool calls.
Depends on: cognition.decision.
"""
from cognition.decision import Decision


def parse_response(raw: str) -> Decision:
    raise NotImplementedError
