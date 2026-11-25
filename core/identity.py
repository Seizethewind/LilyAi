"""Who Lily is: name, role and persona."""
from dataclasses import dataclass


@dataclass(frozen=True)
class Identity:
    name: str = "Lily"
    role: str = "Agent for my project"
    persona: str = ""

    def system_prompt(self) -> str:
        return f"You are {self.name}. {self.role}. {self.persona}".strip()
