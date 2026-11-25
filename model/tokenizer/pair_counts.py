"""Count adjacent token pairs.

Purpose: find the most frequent pair during BPE training.
Depends on: collections, merge (for the Pair type).
"""
from collections import Counter
from typing import Iterable

from model.tokenizer.merge import Pair


def count_pairs(sequences: Iterable[list[int]]) -> Counter[Pair]:
    raise NotImplementedError
