"""Find relevant memories for a query (naive keyword overlap)."""


def retrieve(query: str, records: list[str], top_k: int = 5) -> list[str]:
    words = set(query.lower().split())
    scored = [(len(words & set(r.lower().split())), r) for r in records]
    scored = [s for s in scored if s[0] > 0]
    scored.sort(key=lambda s: s[0], reverse=True)
    return [r for _, r in scored[:top_k]]
