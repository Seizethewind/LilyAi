"""Run the model to produce text from a prompt."""
from model.model import Model


class InferenceEngine:
    def __init__(self, model: Model) -> None:
        self.model = model

    def generate(self, prompt: str, max_new_tokens: int = 128) -> str:
        raise NotImplementedError
