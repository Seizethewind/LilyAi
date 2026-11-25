"""Evaluate a model on held-out data."""
from typing import Any

from training.data_loader import DataLoader


def evaluate(model: Any, data_loader: DataLoader) -> dict[str, float]:
    raise NotImplementedError
