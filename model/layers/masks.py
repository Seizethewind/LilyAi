"""Attention masks.

Purpose: block attention to future tokens and to padding.
Depends on: nothing.
"""
from typing import Any, Sequence


def causal_mask(seq_len: int) -> Any:
    raise NotImplementedError


def padding_mask(token_ids: Sequence[int], pad_id: int) -> Any:
    raise NotImplementedError
