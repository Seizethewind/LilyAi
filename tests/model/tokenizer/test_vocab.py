import pytest

from model.tokenizer.special_tokens import SPECIAL_TOKENS
from model.tokenizer.vocab import Vocab


def test_starts_with_specials_then_256_bytes() -> None:
    assert len(Vocab()) == len(SPECIAL_TOKENS) + 256


def test_special_ids_come_first_in_order() -> None:
    vocab = Vocab()
    for expected, name in enumerate(SPECIAL_TOKENS):
        assert vocab.special_id(name) == expected


def test_byte_ids_follow_the_specials() -> None:
    assert Vocab().token_to_id(b"\x00") == len(SPECIAL_TOKENS)


def test_every_byte_round_trips() -> None:
    vocab = Vocab()
    for value in range(256):
        token = bytes([value])
        assert vocab.id_to_token(vocab.token_to_id(token)) == token


def test_add_returns_next_id_and_round_trips() -> None:
    vocab = Vocab()
    before = len(vocab)
    new_id = vocab.add(b"he")
    assert new_id == before
    assert len(vocab) == before + 1
    assert vocab.token_to_id(b"he") == new_id
    assert vocab.id_to_token(new_id) == b"he"


def test_add_duplicate_raises() -> None:
    with pytest.raises(ValueError):
        Vocab().add(b"a")


def test_add_empty_raises() -> None:
    with pytest.raises(ValueError):
        Vocab().add(b"")


def test_unknown_token_raises_key_error() -> None:
    with pytest.raises(KeyError):
        Vocab().token_to_id(b"zzz")


@pytest.mark.parametrize("idx", [-1, 10_000])
def test_id_to_token_out_of_range_raises(idx: int) -> None:
    with pytest.raises(IndexError):
        Vocab().id_to_token(idx)


def test_unknown_special_raises() -> None:
    with pytest.raises(KeyError):
        Vocab().special_id("<nope>")


def test_special_name_bytes_are_not_a_regular_token() -> None:
    vocab = Vocab()
    with pytest.raises(KeyError):
        vocab.token_to_id(b"<pad>")
    assert vocab.add(b"<pad>") != vocab.special_id("<pad>")
