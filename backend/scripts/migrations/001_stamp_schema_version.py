"""Bring every document up to schemaVersion 1.

This is the migration the old migrate_schema.py performed implicitly: documents
written before the field existed carry no schemaVersion at all. It has no field
transform — the runner writes schemaVersion itself — so `transform` only has to
say "this document needs no other change".

Keeping it as a real, ledgered migration means a fresh environment records that
it ran, rather than the runner re-scanning every collection forever.
"""
from_version = 0
to_version = 1

collections = ("users", "devices", "challenges", "sessions", "files", "audit_logs")


def transform(doc: dict) -> dict | None:
    """No field changes; the version stamp alone is the upgrade."""
    return {}
