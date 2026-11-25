"""Batching over a dataset."""
from training.dataset import TextDataset


class DataLoader:
    def __init__(self, dataset: TextDataset, batch_size: int, shuffle: bool = True) -> None:
        self.dataset = dataset
        self.batch_size = batch_size
        self.shuffle = shuffle

    def __iter__(self):
        raise NotImplementedError
