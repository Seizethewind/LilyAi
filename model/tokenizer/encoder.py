"""Text to token ids.

Purpose: apply learned merges to new text.
Depends on: pretokenizer, merge, vocab.
"""
from model.tokenizer.merge import Pair
from model.tokenizer.vocab import Vocab


def encode(text: str, vocab: Vocab, merges: list[Pair]) -> list[int]:
    raise NotImplementedError
