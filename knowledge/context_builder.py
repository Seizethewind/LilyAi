"""Assemble prompt context from memory and knowledge snippets."""


def build_context(snippets: list[str], max_chars: int = 4000) -> str:
    out: list[str] = []
    used = 0
    for s in snippets:
        if used + len(s) > max_chars:
            break
        out.append(s)
        used += len(s)
    return "\n\n".join(out)
