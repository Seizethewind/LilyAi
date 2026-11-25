"""Text <-> token ids."""


class Tokenizer:
    def __init__(self, vocab_size: int) -> None:
        self.vocab_size = vocab_size

    def train(self, texts: list[str]) -> None:
        raise NotImplementedError

    def encode(self, text: str) -> list[int]:
        raise NotImplementedError

    def decode(self, ids: list[int]) -> str:
        raise NotImplementedError
