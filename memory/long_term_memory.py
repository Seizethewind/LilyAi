"""Persistent memory store (in-memory placeholder; swap for disk/vector DB)."""


class LongTermMemory:
    def __init__(self, store_path: str) -> None:
        self.store_path = store_path
        self._records: list[str] = []

    def add(self, record: str) -> None:
        self._records.append(record)

    def all(self) -> list[str]:
        return list(self._records)
