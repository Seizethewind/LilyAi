"""One transformer block: attention + feed-forward with residuals."""
from model.attention import MultiHeadAttention


class TransformerBlock:
    def __init__(self, d_model: int, n_heads: int, dropout: float) -> None:
        self.attention = MultiHeadAttention(d_model, n_heads, dropout)

    def __call__(self, x, mask=None):
        raise NotImplementedError
