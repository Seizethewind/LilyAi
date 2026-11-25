"""Client for the LilyAiDataNotes knowledge service."""


class KnowledgeClient:
    """Authorized access to LilyAiDataNotes. The API contract is not defined yet."""

    def __init__(self, base_url: str, api_token: str) -> None:
        self.base_url = base_url
        self._api_token = api_token  # never log or print this

    def query(self, question: str) -> list[str]:
        raise NotImplementedError("LilyAiDataNotes API contract is not defined yet.")
