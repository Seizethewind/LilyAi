"""Training loop."""
from typing import Optional

from config import TrainingConfig
from model.model import Model
from training.data_loader import DataLoader


class Trainer:
    def __init__(
        self,
        model: Model,
        train_loader: DataLoader,
        cfg: TrainingConfig,
        val_loader: Optional[DataLoader] = None,
    ) -> None:
        self.model = model
        self.train_loader = train_loader
        self.val_loader = val_loader
        self.cfg = cfg

    def train(self) -> None:
        raise NotImplementedError
