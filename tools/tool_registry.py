"""Registry of callable tools."""
from typing import Callable


class ToolRegistry:
    def __init__(self) -> None:
        self._tools: dict[str, Callable[..., str]] = {}

    def register(self, name: str, fn: Callable[..., str]) -> None:
        self._tools[name] = fn

    def get(self, name: str) -> Callable[..., str]:
        return self._tools[name]

    def names(self) -> list[str]:
        return sorted(self._tools)
