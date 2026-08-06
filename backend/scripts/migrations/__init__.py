"""Versioned Firestore migrations.

Each migration is a module named `NNN_slug.py` in this package exporting:

    from_version: int            # version this migration upgrades FROM
    to_version:   int            # version documents carry afterwards
    collections:  tuple[str,...] # collections to walk
    def transform(doc: dict) -> dict | None

`transform` receives the document body and returns a dict of field updates, or
None to leave the document alone. The runner adds `schemaVersion` itself — a
migration never writes it.

Why a package and not one script: the previous migrate_schema.py only stamped a
version number. It had no per-version transforms and no record of what had run,
so "advance to v2" meant editing the stamping logic in place and there was no
way to tell, from the data, which migrations an environment had actually seen.
"""
from __future__ import annotations

import importlib
import pkgutil
import re
from dataclasses import dataclass
from types import ModuleType

_NAME_RE = re.compile(r"^(\d{3})_[a-z0-9_]+$")


@dataclass(frozen=True)
class Migration:
    name: str
    from_version: int
    to_version: int
    collections: tuple[str, ...]
    transform: callable

    @property
    def sort_key(self) -> tuple[int, str]:
        return (self.to_version, self.name)


def _load(module: ModuleType, name: str) -> Migration:
    missing = [
        attr for attr in ("from_version", "to_version", "collections", "transform")
        if not hasattr(module, attr)
    ]
    if missing:
        raise ValueError(f"migration {name} is missing: {', '.join(missing)}")
    if module.to_version != module.from_version + 1:
        raise ValueError(
            f"migration {name} jumps {module.from_version}->{module.to_version}; "
            "each migration must advance exactly one version so the chain is total"
        )
    if not module.collections:
        raise ValueError(f"migration {name} declares no collections")
    return Migration(
        name=name,
        from_version=module.from_version,
        to_version=module.to_version,
        collections=tuple(module.collections),
        transform=module.transform,
    )


def discover() -> list[Migration]:
    """All migrations, ordered by target version.

    Raises if the chain has a gap or a duplicate — a partial chain applied in
    order would silently leave documents stranded at an intermediate version.
    """
    found = []
    for info in pkgutil.iter_modules(__path__):
        if not _NAME_RE.match(info.name):
            continue
        found.append(_load(importlib.import_module(f"{__name__}.{info.name}"), info.name))

    found.sort(key=lambda m: m.sort_key)
    seen_targets = [m.to_version for m in found]
    if len(set(seen_targets)) != len(seen_targets):
        raise ValueError(f"duplicate target versions among migrations: {seen_targets}")
    for index, migration in enumerate(found):
        expected_from = 0 if index == 0 else found[index - 1].to_version
        if migration.from_version != expected_from:
            raise ValueError(
                f"migration chain has a gap at {migration.name}: expected "
                f"from_version={expected_from}, got {migration.from_version}"
            )
    return found


def pending(applied: set[str]) -> list[Migration]:
    """Migrations not present in the ledger, in application order."""
    return [m for m in discover() if m.name not in applied]
