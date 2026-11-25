"""Sampling strategies for next-token selection."""


def greedy(logits):
    raise NotImplementedError


def top_k(logits, k: int = 50, temperature: float = 1.0):
    raise NotImplementedError


def top_p(logits, p: float = 0.9, temperature: float = 1.0):
    raise NotImplementedError
