"""Reason about an observation and its context."""
from cognition.perception import Observation


class Reasoning:
    def reason(self, observation: Observation, context: list[str]) -> str:
        raise NotImplementedError(
            "Reasoning needs the trained language model (model/inference.py)."
        )
