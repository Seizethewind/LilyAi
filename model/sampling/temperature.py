"""Temperature scaling.

Purpose: sharpen or flatten the logits before sampling.
Depends on: nothing.
"""
from typing import Sequence


def apply_temperature(logits: Sequence[float], temperature: float) -> list[float]:
    raise NotImplementedError
