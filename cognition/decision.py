"""Choose the next action from a plan."""
from dataclasses import dataclass


@dataclass
class Decision:
    action: str  # "respond" or "use_tool"
    content: str = ""


class DecisionMaker:
    def decide(self, plan: list[str]) -> Decision:
        # TODO: wire in the model and tool calls
        return Decision(action="respond", content="(model not implemented yet)")
