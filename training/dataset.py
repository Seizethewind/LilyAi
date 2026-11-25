"""Text dataset."""


class TextDataset:
    def __init__(self, path: str) -> None:
        self.path = path

    def __len__(self) -> int:
        raise NotImplementedError

    def __getitem__(self, idx: int):
        raise NotImplementedError
