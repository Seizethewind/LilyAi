"""Validate user input before it reaches the agent."""
from config import SafetyConfig


class InputValidator:
    """Rejects empty or oversized input and returns it stripped."""

    def __init__(self, cfg: SafetyConfig) -> None:
        self.cfg = cfg

    def validate(self, text: str) -> str:
        cleaned = text.strip()
        if not cleaned:
            raise ValueError("Input is empty")
        if len(cleaned) > self.cfg.max_input_chars:
            raise ValueError("Input too long")
        return cleaned
