"""Runtime: wires all components together and handles one user turn."""
from cognition.cognition import Cognition
from config import Config
from conversation.conversation import Conversation
from core.identity import Identity
from core.lifecycle import Lifecycle
from memory.memory_manager import MemoryManager
from safety.input_validation import InputValidator
from safety.output_validation import OutputValidator


class Runtime:
    def __init__(self, config: Config) -> None:
        self.config = config
        self.identity = Identity(name=config.name)
        self.lifecycle = Lifecycle()
        self.conversation = Conversation()
        self.memory = MemoryManager(config.memory)
        self.cognition = Cognition(self.memory)
        self.input_validator = InputValidator(config.safety)
        self.output_validator = OutputValidator(config.safety)

    def start(self) -> None:
        self.lifecycle.start()

    def stop(self) -> None:
        self.lifecycle.stop()

    def handle(self, text: str) -> str:
        text = self.input_validator.validate(text)
        self.conversation.add("user", text)
        decision = self.cognition.step(text, self.conversation)
        reply = self.output_validator.validate(decision.content)
        self.conversation.add("assistant", reply)
        return reply
