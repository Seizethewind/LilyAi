"""Multi-head self-attention."""


class MultiHeadAttention:
    def __init__(self, d_model: int, n_heads: int, dropout: float) -> None:
        self.d_model = d_model
        self.n_heads = n_heads
        self.dropout = dropout

    def __call__(self, x, mask=None):
        raise NotImplementedError
