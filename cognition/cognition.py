"""Cognitive loop: perceive -> reason -> plan -> decide."""
from cognition.decision import Decision, DecisionMaker
from cognition.perception import Perception
from cognition.planning import Planning
from cognition.reasoning import Reasoning
from conversation.conversation import Conversation
from memory.memory_manager import MemoryManager


class Cognition:
    def __init__(self, memory: MemoryManager) -> None:
        self.memory = memory
        self.perception = Perception()
        self.reasoning = Reasoning()
        self.planning = Planning()
        self.decider = DecisionMaker()

    def step(self, text: str, conversation: Conversation) -> Decision:
        observation = self.perception.perceive(text)
        context = self.memory.recall(observation.text)
        thought = self.reasoning.reason(observation, context)
        plan = self.planning.plan(thought)
        return self.decider.decide(plan)
