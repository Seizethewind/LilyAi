"""Special token names.

Purpose: one place that defines the reserved tokens.
Depends on: nothing.
"""

PAD = "<pad>"
UNK = "<unk>"
BOS = "<bos>"
EOS = "<eos>"

SPECIAL_TOKENS: tuple[str, ...] = (PAD, UNK, BOS, EOS)
