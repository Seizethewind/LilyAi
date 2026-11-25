"""Retrieve relevant documents/chunks for a query."""


class DocumentRetrieval:
    def search(self, query: str, top_k: int = 5) -> list[str]:
        raise NotImplementedError
