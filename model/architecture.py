"""Architecture description built from ModelConfig."""
from dataclasses import dataclass

from config import ModelConfig


@dataclass
class Architecture:
    vocab_size: int
    max_seq_len: int
    d_model: int
    n_layers: int
    n_heads: int
    dropout: float

    @classmethod
    def from_config(cls, cfg: ModelConfig) -> "Architecture":
        return cls(**vars(cfg))
