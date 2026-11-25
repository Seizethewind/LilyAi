"""Learn BPE merge rules from a corpus.

Purpose: training loop that grows the vocab to the target size.
Depends on: pretokenizer, pair_counts, merge, vocab.
"""
from typing import Iterable

from model.tokenizer.merge import Pair
from model.tokenizer.vocab import Vocab


def train_bpe(texts: Iterable[str], vocab_size: int) -> tuple[Vocab, list[Pair]]:
    raise NotImplementedError
