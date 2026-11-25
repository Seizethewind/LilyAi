"""Token and position embeddings."""


class Embeddings:
    def __init__(self, vocab_size: int, d_model: int, max_seq_len: int) -> None:
        self.vocab_size = vocab_size
        self.d_model = d_model
        self.max_seq_len = max_seq_len

    def __call__(self, token_ids):
        raise NotImplementedError
