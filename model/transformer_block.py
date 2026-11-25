"""One transformer block: attention + feed-forward with residuals."""
from typing import Any

from model.attention import MultiHeadAttention


class TransformerBlock:
    def __init__(self, d_model: int, n_heads: int, dropout: float) -> None:
        self.attention = MultiHeadAttention(d_model, n_heads, dropout)

    def __call__(self, x: Any, mask: Any = None) -> Any:
        raise NotImplementedError
