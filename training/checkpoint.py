"""Save and load training checkpoints."""


def save_checkpoint(model, optimizer, step: int, path: str) -> None:
    raise NotImplementedError


def load_checkpoint(path: str):
    raise NotImplementedError
