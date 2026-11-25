"""Gradient clipping.

Purpose: keep training stable.
Depends on: nothing.
"""
from typing import Any


def clip_gradients(parameters: Any, max_norm: float) -> float:
    raise NotImplementedError
