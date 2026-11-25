"""Validate user input before it reaches the agent."""
from config import SafetyConfig


class InputValidator:
    def __init__(self, cfg: SafetyConfig) -> None:
        self.cfg = cfg

    def validate(self, text: str) -> str:
        if len(text) > self.cfg.max_input_chars:
            raise ValueError("Input too long")
        return text
