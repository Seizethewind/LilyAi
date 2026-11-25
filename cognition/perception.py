"""Turn raw input into a structured observation."""
from dataclasses import dataclass


@dataclass
class Observation:
    text: str


class Perception:
    def perceive(self, text: str) -> Observation:
        return Observation(text=text.strip())
