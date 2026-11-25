"""Persistent memory store backed by a JSON Lines file."""
import json
from pathlib import Path


class LongTermMemory:
    """Append-only record store saved at <store_path>/records.jsonl."""

    def __init__(self, store_path: str) -> None:
        self._file = Path(store_path) / "records.jsonl"
        self._records: list[str] = self._load()

    def _load(self) -> list[str]:
        if not self._file.exists():
            return []
        with self._file.open(encoding="utf-8") as fh:
            return [json.loads(line)["text"] for line in fh if line.strip()]

    def add(self, record: str) -> None:
        self._file.parent.mkdir(parents=True, exist_ok=True)
        with self._file.open("a", encoding="utf-8") as fh:
            fh.write(json.dumps({"text": record}) + "\n")
        self._records.append(record)

    def all(self) -> list[str]:
        return list(self._records)
