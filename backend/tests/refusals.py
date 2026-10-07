"""Repo calls that refuse by raising `errors.Refusal`, read back as `(code, value)`.

For tests that check which code a call refused with, or that it did not refuse
(`code == ""`), without a `pytest.raises` block around every attempt.
"""
from app import errors


def attempt(fn, *args, **kwargs):
    """`("", answer)`, or `(code, None)` when `fn` refuses."""
    try:
        return "", fn(*args, **kwargs)
    except errors.Refusal as refusal:
        return refusal.code, None


def attempt_add(fn, *args, **kwargs):
    """`attempt` for `add_institution_member`: `(code, seat, invite)`."""
    code, out = attempt(fn, *args, **kwargs)
    return (code, *(out or (None, None)))
