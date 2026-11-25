"""Short-term memory: the most recent items, fixed capacity."""
from collections import deque


class WorkingMemory:
    def __init__(self, capacity: int = 32) -> None:
        self._items: deque[str] = deque(maxlen=capacity)

    def add(self, item: str) -> None:
        self._items.append(item)

    def items(self) -> list[str]:
        return list(self._items)

    def clear(self) -> None:
        self._items.clear()
