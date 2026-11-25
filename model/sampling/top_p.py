"""Top-p (nucleus) filter.

Purpose: keep the smallest set of tokens whose probability reaches p.
Depends on: nothing.
"""
from typing import Sequence


def top_p_filter(logits: Sequence[float], p: float) -> list[float]:
    raise NotImplementedError
