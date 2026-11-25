"""Training loop."""
from config import TrainingConfig


class Trainer:
    def __init__(self, model, train_loader, cfg: TrainingConfig, val_loader=None) -> None:
        self.model = model
        self.train_loader = train_loader
        self.val_loader = val_loader
        self.cfg = cfg

    def train(self) -> None:
        raise NotImplementedError
