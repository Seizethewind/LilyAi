"""Split raw text into word-like chunks before merging.

Purpose: keep BPE merges from crossing word boundaries.
Depends on: re (standard library).

The rules follow the GPT-2 style: a chunk is a contraction suffix ('s, 't,
're, 've, 'm, 'll, 'd), a run of letters, a run of digits, a run of other
symbols, or whitespace. A single leading space stays attached to the word or
symbol run that follows it. The standard `re` module has no `\\p{L}` classes,
so letters are `[^\\W\\d_]` and symbols are anything that is not a word
character or whitespace, plus the underscore.

Splitting is lossless: joining the chunks always gives back the input text.
"""
import re

_PATTERN = re.compile(
    r"'(?:[sdmt]|ll|ve|re)"
    r"| ?[^\W\d_]+"
    r"| ?\d+"
    r"| ?(?:[^\w\s]|_)+"
    r"|\s+(?!\S)"
    r"|\s+"
)


def pretokenize(text: str) -> list[str]:
    return _PATTERN.findall(text)
