"""Train/validation split.

Purpose: hold out data for evaluation.
Depends on: nothing.
"""
from typing import Sequence, TypeVar

T = TypeVar("T")


def split_train_val(
    items: Sequence[T], val_fraction: float, seed: int
) -> tuple[list[T], list[T]]:
    raise NotImplementedError
