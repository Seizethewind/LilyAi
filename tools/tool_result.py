"""Outcome of a tool call.

Purpose: a uniform result type for the executor.
Depends on: nothing.
"""
from dataclasses import dataclass


@dataclass(frozen=True)
class ToolResult:
    ok: bool
    output: str
