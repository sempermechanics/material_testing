"""The legal documents the app must have the user accept, and their versions.

`TERMS_VERSION` is the `**Version:**` line of `docs/legal/TERMS_OF_SERVICE.md`.
The app gates sign-in on it: a profile whose `termsAccepted.version` differs is
sent back to the acceptance screen. Bumping the document without bumping this
constant would silently let every existing user continue under terms they
never saw — `tests/test_legal_version.py` fails the build if the two drift.
"""

TERMS_VERSION = "2026-09-15"
TERMS_URL = "https://sempermechanics.com/terms/"
PRIVACY_URL = "https://sempermechanics.com/privacy/"
