"""Which tools the agent may use."""
from config import SafetyConfig


class Permissions:
    def __init__(self, cfg: SafetyConfig) -> None:
        self.allowed = set(cfg.allowed_tools)

    def is_allowed(self, tool: str) -> bool:
        return tool in self.allowed
