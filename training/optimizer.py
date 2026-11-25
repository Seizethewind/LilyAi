"""Optimizer and learning-rate schedule."""
from typing import Any


def build_optimizer(model: Any, learning_rate: float) -> Any:
    raise NotImplementedError


def lr_schedule(step: int, warmup: int, total: int, base_lr: float) -> float:
    raise NotImplementedError
