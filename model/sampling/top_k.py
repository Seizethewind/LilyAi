"""Top-k filter.

Purpose: keep only the k highest logits.
Depends on: nothing.
"""
from typing import Sequence


def top_k_filter(logits: Sequence[float], k: int) -> list[float]:
    raise NotImplementedError
