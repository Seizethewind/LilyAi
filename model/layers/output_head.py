"""Output projection from hidden states to vocabulary logits.

Purpose: final layer of the language model.
Depends on: nothing.
"""
from typing import Any


class OutputHead:
    def __init__(self, d_model: int, vocab_size: int) -> None:
        self.d_model = d_model
        self.vocab_size = vocab_size

    def __call__(self, x: Any) -> Any:
        raise NotImplementedError
