"""Position information for token embeddings.

Purpose: tell the model where each token sits in the sequence.
Depends on: nothing. Tensor type is `Any` until the ML library is chosen.
"""
from typing import Any


def positional_encoding(seq_len: int, d_model: int) -> Any:
    raise NotImplementedError
