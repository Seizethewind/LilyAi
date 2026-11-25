"""Token <-> id tables.

Purpose: store the vocabulary (special tokens, 256 byte tokens, learned merges).
Depends on: special_tokens.

Id layout: special tokens first, then the 256 single-byte tokens, then every
token added with `add` (the learned merges), in the order they were added.
Special tokens live in their own table, so a learned token whose bytes spell
a special name (for example b"<pad>") gets its own, separate id.
"""
from model.tokenizer.special_tokens import SPECIAL_TOKENS


class Vocab:
    def __init__(self) -> None:
        self._token_to_id: dict[bytes, int] = {}
        self._id_to_token: list[bytes] = []
        self._special_ids: dict[str, int] = {}
        for name in SPECIAL_TOKENS:
            self._special_ids[name] = len(self._id_to_token)
            self._id_to_token.append(name.encode("utf-8"))
        for value in range(256):
            self.add(bytes([value]))

    def __len__(self) -> int:
        return len(self._id_to_token)

    def add(self, token: bytes) -> int:
        """Append a new token and return its id. Duplicates and empty tokens are errors."""
        if not token:
            raise ValueError("Cannot add an empty token")
        if token in self._token_to_id:
            raise ValueError(f"Token already in vocab: {token!r}")
        idx = len(self._id_to_token)
        self._token_to_id[token] = idx
        self._id_to_token.append(token)
        return idx

    def token_to_id(self, token: bytes) -> int:
        try:
            return self._token_to_id[token]
        except KeyError:
            raise KeyError(f"Unknown token: {token!r}") from None

    def id_to_token(self, idx: int) -> bytes:
        if not 0 <= idx < len(self._id_to_token):
            raise IndexError(f"Token id out of range: {idx}")
        return self._id_to_token[idx]

    def special_id(self, name: str) -> int:
        try:
            return self._special_ids[name]
        except KeyError:
            raise KeyError(f"Unknown special token: {name!r}") from None
