"""Draw one token id from filtered logits.

Purpose: the final sampling step after temperature and filters.
Depends on: nothing.
"""
from typing import Sequence


def sample_token(logits: Sequence[float]) -> int:
    raise NotImplementedError
