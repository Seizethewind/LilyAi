"""Token <-> id tables.

Purpose: store the vocabulary (byte tokens plus learned merges).
Depends on: special_tokens.
"""


class Vocab:
    def __init__(self) -> None:
        self._token_to_id: dict[bytes, int] = {}
        self._id_to_token: list[bytes] = []

    def __len__(self) -> int:
        return len(self._id_to_token)

    def add(self, token: bytes) -> int:
        raise NotImplementedError

    def token_to_id(self, token: bytes) -> int:
        raise NotImplementedError

    def id_to_token(self, idx: int) -> bytes:
        raise NotImplementedError

    def special_id(self, name: str) -> int:
        raise NotImplementedError
