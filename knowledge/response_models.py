"""Internal result type for knowledge lookups.

Purpose: what the rest of LilyAi receives from the knowledge layer.
Depends on: nothing. Mapping from the API response is added once the contract exists.
"""
from dataclasses import dataclass, field


@dataclass(frozen=True)
class KnowledgeResponse:
    snippets: list[str] = field(default_factory=list)
