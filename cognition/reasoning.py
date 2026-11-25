"""Reason about an observation and its context."""
from cognition.perception import Observation


class Reasoning:
    def reason(self, observation: Observation, context: list[str]) -> str:
        # TODO: call the language model / chain of thought
        return observation.text
