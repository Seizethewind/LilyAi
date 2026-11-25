"""Decoder-only language model: embeddings + blocks + output head."""
from typing import Any, Sequence

from model.architecture import Architecture
from model.embeddings import Embeddings
from model.transformer_block import TransformerBlock


class LanguageModel:
    def __init__(self, arch: Architecture) -> None:
        self.arch = arch
        self.embeddings = Embeddings(arch.vocab_size, arch.d_model, arch.max_seq_len)
        self.blocks = [
            TransformerBlock(arch.d_model, arch.n_heads, arch.dropout)
            for _ in range(arch.n_layers)
        ]

    def forward(self, token_ids: Sequence[int]) -> Any:
        raise NotImplementedError
