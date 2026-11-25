"""Multi-head self-attention."""
from typing import Any


class MultiHeadAttention:
    def __init__(self, d_model: int, n_heads: int, dropout: float) -> None:
        self.d_model = d_model
        self.n_heads = n_heads
        self.dropout = dropout

    def __call__(self, x: Any, mask: Any = None) -> Any:
        raise NotImplementedError
