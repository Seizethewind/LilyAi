"""Optimizer construction.

Purpose: build the optimizer for training.
Depends on: nothing. Optimizer type is `Any` until the ML library is chosen.
"""
from typing import Any


def build_optimizer(model: Any, learning_rate: float) -> Any:
    raise NotImplementedError
