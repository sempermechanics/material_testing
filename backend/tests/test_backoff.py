"""Outbound retry delays honour Retry-After but never park a thread (TD-52)."""
import requests

from app import backoff, drive, notify


def _response(retry_after):
    r = requests.Response()
    if retry_after is not None:
        r.headers["Retry-After"] = retry_after
    return r


def test_retry_after_is_honoured_within_the_cap():
    assert backoff.retry_delay(0, "3") == 3.0
    assert backoff.retry_delay(0, "0.2", floor=0.5) == 0.5


def test_a_huge_or_infinite_retry_after_is_capped():
    assert backoff.retry_delay(0, "3600") == backoff.MAX_DELAY_S
    assert backoff.retry_delay(0, "inf") == backoff.MAX_DELAY_S


def test_garbage_and_nan_fall_back_to_exponential():
    # An HTTP-date or junk is not a number of seconds.
    assert backoff.retry_delay(2, "Wed, 21 Oct 2015 07:28:00 GMT") == 4.0
    assert backoff.retry_delay(2, "nan") == 4.0
    assert backoff.retry_delay(10, None) == backoff.MAX_DELAY_S
    assert backoff.retry_delay(0, "-5") == 0.0


def test_drive_and_notify_share_the_cap():
    assert drive._retry_delay(_response("3600"), 0) == backoff.MAX_DELAY_S
    assert notify._retry_delay(0, _response("3600")) == backoff.MAX_DELAY_S
    assert notify._retry_delay(1, None) == 2.0
    assert notify._retry_delay(0, _response("0")) == 0.5
