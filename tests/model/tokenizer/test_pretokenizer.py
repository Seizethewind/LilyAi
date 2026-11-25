import random

import pytest

from model.tokenizer.pretokenizer import pretokenize


def test_empty_text_gives_no_chunks() -> None:
    assert pretokenize("") == []


@pytest.mark.parametrize(
    "text,expected",
    [
        ("Hello world", ["Hello", " world"]),
        ("don't", ["don", "'t"]),
        ("I'll go, you're late", ["I", "'ll", " go", ",", " you", "'re", " late"]),
        ("abc123", ["abc", "123"]),
        (" 42", [" 42"]),
        ("Hi!!", ["Hi", "!!"]),
        ("a, b", ["a", ",", " b"]),
        ("a  b", ["a", " ", " b"]),
        ("a\nb", ["a", "\n", "b"]),
        ("a\n\nb", ["a", "\n", "\n", "b"]),
        ("snake_case", ["snake", "_", "case"]),
        ("héllo wörld", ["héllo", " wörld"]),
        ("你好 世界", ["你好", " 世界"]),
        ("hi 😀", ["hi", " 😀"]),
        ("end ", ["end", " "]),
    ],
)
def test_known_splits(text: str, expected: list[str]) -> None:
    assert pretokenize(text) == expected


@pytest.mark.parametrize(
    "text",
    ["", " ", "  ", "\t\t", "\n", "'", "'s", "a\tb", " \n ", "x_y_z", "😀😀", "tab\t \nmix"],
)
def test_split_is_lossless_for_edge_cases(text: str) -> None:
    assert "".join(pretokenize(text)) == text


def test_split_is_lossless_and_never_empty_for_random_text() -> None:
    rng = random.Random(1234)
    alphabet = list("abcXYZ019 _\t\n'.,!?-é你😀") + ["'s", "'ll", "  "]
    for _ in range(500):
        text = "".join(rng.choice(alphabet) for _ in range(rng.randint(0, 40)))
        chunks = pretokenize(text)
        assert "".join(chunks) == text
        assert all(chunks)
