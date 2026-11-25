"""Client for external knowledge sources."""


class KnowledgeClient:
    def query(self, question: str) -> list[str]:
        raise NotImplementedError
