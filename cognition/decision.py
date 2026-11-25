"""Choose the next action from a plan."""
from dataclasses import dataclass


@dataclass
class Decision:
    action: str  # "respond" or "use_tool"
    content: str = ""


class DecisionMaker:
    def decide(self, plan: list[str]) -> Decision:
        raise NotImplementedError("Decision making needs the model and tool layer.")
