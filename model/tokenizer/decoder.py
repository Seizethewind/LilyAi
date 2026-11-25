"""Token ids back to text.

Purpose: invert encoding.
Depends on: vocab.
"""
from typing import Sequence

from model.tokenizer.vocab import Vocab


def decode(ids: Sequence[int], vocab: Vocab) -> str:
    raise NotImplementedError
