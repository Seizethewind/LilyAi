"""Validate agent output before it is shown."""
from config import SafetyConfig


class OutputValidator:
    def __init__(self, cfg: SafetyConfig) -> None:
        self.cfg = cfg

    def validate(self, text: str) -> str:
        # TODO: content checks
        return text
