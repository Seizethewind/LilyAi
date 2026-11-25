"""Authorization headers for LilyAiDataNotes.

Purpose: build request headers from the API token.
Depends on: nothing. The auth scheme is not defined yet.
"""


def auth_headers(api_token: str) -> dict[str, str]:
    raise NotImplementedError("LilyAiDataNotes auth scheme is not defined yet.")
