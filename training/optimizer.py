"""Optimizer and learning-rate schedule."""


def build_optimizer(model, learning_rate: float):
    raise NotImplementedError


def lr_schedule(step: int, warmup: int, total: int, base_lr: float) -> float:
    raise NotImplementedError
