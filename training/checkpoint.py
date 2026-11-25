"""Save and load training checkpoints."""
from typing import Any


def save_checkpoint(model: Any, optimizer: Any, step: int, path: str) -> None:
    raise NotImplementedError


def load_checkpoint(path: str) -> dict[str, Any]:
    raise NotImplementedError
