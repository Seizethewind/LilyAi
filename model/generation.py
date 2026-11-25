"""Sampling strategies for next-token selection."""
from typing import Sequence


def greedy(logits: Sequence[float]) -> int:
    raise NotImplementedError


def top_k(logits: Sequence[float], k: int = 50, temperature: float = 1.0) -> int:
    raise NotImplementedError


def top_p(logits: Sequence[float], p: float = 0.9, temperature: float = 1.0) -> int:
    raise NotImplementedError
