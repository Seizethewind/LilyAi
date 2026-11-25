"""Apply one merge rule to a token-id sequence.

Purpose: the single merge step used by training and encoding.
Depends on: nothing.
"""

Pair = tuple[int, int]


def merge_pair(ids: list[int], pair: Pair, new_id: int) -> list[int]:
    raise NotImplementedError
