"""A single chat message."""
from dataclasses import dataclass, field
from datetime import datetime, timezone


@dataclass
class Message:
    role: str  # "user", "assistant" or "system"
    content: str
    timestamp: datetime = field(default_factory=lambda: datetime.now(timezone.utc))
