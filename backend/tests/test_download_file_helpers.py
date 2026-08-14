"""_is_first_byte_request decides which Range window of a proxied restore
download gets the (single) FILE_DOWNLOAD audit write — a restore fetches one
file in many adaptive-size windows (DriveTransfer.nextWindowBytes on the
client), and logging every window would be one Firestore write per window
instead of one per file."""
from app.main import _is_first_byte_request


def test_no_range_header_is_first():
    # A whole-file GET (no windowing at all) counts as "first".
    assert _is_first_byte_request(None) is True
    assert _is_first_byte_request("") is True


def test_window_starting_at_zero_is_first():
    assert _is_first_byte_request("bytes=0-1048575") is True
    assert _is_first_byte_request("bytes=0-") is True


def test_window_starting_past_zero_is_not_first():
    assert _is_first_byte_request("bytes=1048576-2097151") is False
    assert _is_first_byte_request("bytes=9000-9499") is False


def test_malformed_range_is_not_first():
    # Fails open toward "don't log" rather than crashing on a header we can't parse —
    # a malformed Range would fail elsewhere in the request anyway.
    assert _is_first_byte_request("not-a-range") is False
    assert _is_first_byte_request("bytes=") is False


def test_whitespace_is_tolerated():
    assert _is_first_byte_request(" bytes=0-999 ") is True
