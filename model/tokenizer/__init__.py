"""Byte-level BPE tokenizer package.

Public entry point: `Tokenizer`. It wires the single-purpose modules in this
package together. Standard library only.
"""
from typing import Iterable, Sequence

from model.tokenizer.bpe_trainer import train_bpe
from model.tokenizer.decoder import decode as decode_ids
from model.tokenizer.encoder import encode as encode_text
from model.tokenizer.merge import Pair
from model.tokenizer.serialization import load_tokenizer, save_tokenizer
from model.tokenizer.vocab import Vocab


class Tokenizer:
    def __init__(self, vocab_size: int) -> None:
        self.vocab_size = vocab_size
        self.vocab = Vocab()
        self.merges: list[Pair] = []

    def train(self, texts: Iterable[str]) -> None:
        self.vocab, self.merges = train_bpe(texts, self.vocab_size)

    def encode(self, text: str) -> list[int]:
        return encode_text(text, self.vocab, self.merges)

    def decode(self, ids: Sequence[int]) -> str:
        return decode_ids(ids, self.vocab)

    def save(self, path: str) -> None:
        save_tokenizer(path, self.vocab, self.merges)

    def load(self, path: str) -> None:
        self.vocab, self.merges = load_tokenizer(path)


__all__ = ["Tokenizer"]
