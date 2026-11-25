"""Split raw text into word-like chunks before merging.

Purpose: keep merges from crossing word boundaries.
Depends on: re (standard library).
"""


def pretokenize(text: str) -> list[str]:
    raise NotImplementedError
