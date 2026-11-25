"""Description of a tool the agent can call.

Purpose: the shape tools are registered and presented with.
Depends on: nothing.
"""
from dataclasses import dataclass, field


@dataclass(frozen=True)
class ToolSchema:
    name: str
    description: str
    parameters: dict[str, str] = field(default_factory=dict)
