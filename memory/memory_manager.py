"""Coordinates working and long-term memory."""
from config import MemoryConfig
from memory.long_term_memory import LongTermMemory
from memory.retrieval import retrieve
from memory.working_memory import WorkingMemory


class MemoryManager:
    def __init__(self, cfg: MemoryConfig) -> None:
        self.working = WorkingMemory(cfg.working_capacity)
        self.long_term = LongTermMemory(cfg.store_path)

    def remember(self, item: str, persist: bool = False) -> None:
        self.working.add(item)
        if persist:
            self.long_term.add(item)

    def recall(self, query: str) -> list[str]:
        return self.working.items() + retrieve(query, self.long_term.all())
