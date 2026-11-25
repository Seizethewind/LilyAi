"""Save and load a trained tokenizer as JSON.

Purpose: persist vocab and merges.
Depends on: json, pathlib (standard library), vocab, merge.
"""
from model.tokenizer.merge import Pair
from model.tokenizer.vocab import Vocab


def save_tokenizer(path: str, vocab: Vocab, merges: list[Pair]) -> None:
    raise NotImplementedError


def load_tokenizer(path: str) -> tuple[Vocab, list[Pair]]:
    raise NotImplementedError
