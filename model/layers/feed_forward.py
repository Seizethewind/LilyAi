"""Position-wise feed-forward network.

Purpose: the non-attention half of a transformer block.
Depends on: nothing.
"""
from typing import Any


class FeedForward:
    def __init__(self, d_model: int, d_hidden: int, dropout: float) -> None:
        self.d_model = d_model
        self.d_hidden = d_hidden
        self.dropout = dropout

    def __call__(self, x: Any) -> Any:
        raise NotImplementedError
