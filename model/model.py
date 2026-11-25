"""High-level model wrapper: tokenizer + language model + save/load."""
from config import ModelConfig
from model.architecture import Architecture
from model.language_model import LanguageModel
from model.tokenizer import Tokenizer


class Model:
    def __init__(self, cfg: ModelConfig) -> None:
        self.cfg = cfg
        self.tokenizer = Tokenizer(cfg.vocab_size)
        self.lm = LanguageModel(Architecture.from_config(cfg))

    def save(self, path: str) -> None:
        raise NotImplementedError

    def load(self, path: str) -> None:
        raise NotImplementedError
