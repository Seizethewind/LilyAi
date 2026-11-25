"""HTTP transport for the LilyAiDataNotes API.

Purpose: send authorized requests and return parsed JSON.
Depends on: api_auth. The API contract is not defined yet.
"""
from typing import Any


class HttpClient:
    def __init__(self, base_url: str, api_token: str) -> None:
        self.base_url = base_url
        self._api_token = api_token  # never log or print this

    def get_json(self, path: str, params: dict[str, str] | None = None) -> Any:
        raise NotImplementedError("LilyAiDataNotes API contract is not defined yet.")
