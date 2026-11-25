"""Learning-rate schedule.

Purpose: warmup then decay.
Depends on: nothing.
"""


def lr_at_step(step: int, warmup: int, total: int, base_lr: float) -> float:
    raise NotImplementedError
