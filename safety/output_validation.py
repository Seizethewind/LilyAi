"""Validate agent output before it is shown."""
from config import SafetyConfig


class OutputValidator:
    """Rejects empty or oversized output and returns it stripped."""

    def __init__(self, cfg: SafetyConfig) -> None:
        self.cfg = cfg

    def validate(self, text: str) -> str:
        cleaned = text.strip()
        if not cleaned:
            raise ValueError("Output is empty")
        if len(cleaned) > self.cfg.max_output_chars:
            raise ValueError("Output too long")
        return cleaned
