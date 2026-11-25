"""Layer normalization.

Purpose: stabilize activations inside each transformer block.
Depends on: nothing.
"""
from typing import Any


class LayerNorm:
    def __init__(self, d_model: int, eps: float = 1e-5) -> None:
        self.d_model = d_model
        self.eps = eps

    def __call__(self, x: Any) -> Any:
        raise NotImplementedError
