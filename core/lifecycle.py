"""Lifecycle states and start/stop hooks."""
from enum import Enum, auto
from typing import Callable


class State(Enum):
    CREATED = auto()
    RUNNING = auto()
    STOPPED = auto()


class Lifecycle:
    def __init__(self) -> None:
        self.state = State.CREATED
        self._on_start: list[Callable[[], None]] = []
        self._on_stop: list[Callable[[], None]] = []

    def on_start(self, hook: Callable[[], None]) -> None:
        self._on_start.append(hook)

    def on_stop(self, hook: Callable[[], None]) -> None:
        self._on_stop.append(hook)

    def start(self) -> None:
        for hook in self._on_start:
            hook()
        self.state = State.RUNNING

    def stop(self) -> None:
        for hook in reversed(self._on_stop):
            hook()
        self.state = State.STOPPED
