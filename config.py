"""Central configuration for LilyAi."""
from dataclasses import dataclass, field


@dataclass
class ModelConfig:
    vocab_size: int = 32000
    max_seq_len: int = 1024
    d_model: int = 512
    n_layers: int = 8
    n_heads: int = 8
    dropout: float = 0.1


@dataclass
class TrainingConfig:
    batch_size: int = 16
    learning_rate: float = 3e-4
    epochs: int = 1
    checkpoint_dir: str = "checkpoints"


@dataclass
class MemoryConfig:
    working_capacity: int = 32
    store_path: str = "data/memory"


@dataclass
class SafetyConfig:
    max_input_chars: int = 8000
    allowed_tools: list[str] = field(default_factory=list)


@dataclass
class Config:
    name: str = "Lily"
    model: ModelConfig = field(default_factory=ModelConfig)
    training: TrainingConfig = field(default_factory=TrainingConfig)
    memory: MemoryConfig = field(default_factory=MemoryConfig)
    safety: SafetyConfig = field(default_factory=SafetyConfig)
