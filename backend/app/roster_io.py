"""The roster's files and counts: CSV in, CSV out, the seat summary.

Pure functions over what `repo` returns, kept apart from `routers/roster.py`
so the routes stay a page of wiring and these can be tested without a
request. Nothing here reads or writes Firestore.
"""
from __future__ import annotations

import csv
import io
import json
from dataclasses import dataclass
from datetime import date, datetime, time, timedelta, timezone

from . import apps, errors
from .licenses import SEATING_FLOATING, as_utc, normalize_email, normalize_seating
from .repo import NO_CHECKIN_SINCE_REVOKE

#: The most rows one import may hold. Each is a seat add (a transaction on
#: the licence's counter) and an audit write; 500 is a class or a department,
#: and keeps one request inside the gateway's 60 s deadline.
MAX_IMPORT_ROWS = 500
#: Comfortably over 500 rows of 320-character addresses plus a few columns,
#: which is what an exported roster re-imported carries.
MAX_IMPORT_CHARS = 512_000

#: "Active" in the seat summary: the holder's account made a request this
#: recently. `users.lastSeenAt` is written at most hourly, which is far finer
#: than a week.
ACTIVE_WINDOW = timedelta(days=7)
#: "Expiring" in the seat summary: a timed licence ending this soon.
EXPIRING_WINDOW = timedelta(days=30)


class RosterRefused(ValueError):
    """A whole request refused before anything was read or written: an import
    that cannot be parsed, an audit range that is not one. `code` is the wire
    code, answered as a 400 (or 413 for too many rows)."""

    def __init__(self, code: str):
        super().__init__(code)
        self.code = code


@dataclass(frozen=True)
class ImportRow:
    """One data row of an import: its line in the file and the address as typed."""
    row: int
    email: str


def parse_import(text: str) -> list[ImportRow]:
    """The rows of a roster CSV, or raise `RosterRefused`.

    One column is read: the address. A first record with no `@` in any cell
    is a header, and its `email` column (any case) is the one read, so a
    roster this service exported imports as it is. Without a header the first
    column is the address. Blank lines are skipped; every other record is a
    row, reported by its line number in the file so whoever is fixing it can
    find it. A row with nothing in the address column is still a row: it is
    answered `invalid` rather than silently dropped.
    """
    reader = csv.reader(io.StringIO(text.lstrip("﻿")), strict=True)
    records = []
    try:
        for record in reader:
            if any(cell.strip() for cell in record):
                records.append((reader.line_num, record))
    except csv.Error as e:
        raise RosterRefused(errors.ROSTER_IMPORT_MALFORMED) from e
    if not records:
        raise RosterRefused(errors.ROSTER_IMPORT_EMPTY)
    column = 0
    _, first = records[0]
    if not any("@" in cell for cell in first):
        names = [cell.strip().lower() for cell in first]
        if "email" not in names:
            raise RosterRefused(errors.ROSTER_IMPORT_NO_EMAIL_COLUMN)
        column = names.index("email")
        records = records[1:]
    if not records:
        raise RosterRefused(errors.ROSTER_IMPORT_EMPTY)
    if len(records) > MAX_IMPORT_ROWS:
        raise RosterRefused(errors.ROSTER_IMPORT_TOO_MANY_ROWS)
    return [
        ImportRow(line, (record[column] if column < len(record) else "").strip())
        for line, record in records
    ]


# ---------------- CSV out ----------------

#: Leading characters a spreadsheet reads as the start of a formula. An
#: address or a detail value is the caller's own data, but a roster CSV is
#: opened in Excel by someone else, and `=HYPERLINK(...)@x.edu` is a valid
#: local part.
_FORMULA_LEAD = ("=", "+", "-", "@", "\t", "\r")


def _cell(value) -> str:
    """One CSV cell: datetimes as ISO-8601 UTC, None as empty, formulas defused."""
    if value is None:
        return ""
    if isinstance(value, datetime):
        value = iso(value)
    text = str(value)
    return f"'{text}" if text.startswith(_FORMULA_LEAD) else text


def iso(value) -> str:
    """A stored instant as `2026-10-18T09:30:00Z`; "" when there is none."""
    instant = as_utc(value)
    if instant is None:
        return str(value) if value else ""
    return instant.isoformat().replace("+00:00", "Z")


def _render(header: list[str], rows: list[list]) -> str:
    out = io.StringIO()
    writer = csv.writer(out, lineterminator="\r\n")
    writer.writerow(header)
    for row in rows:
        writer.writerow([_cell(v) for v in row])
    return out.getvalue()


#: The roster export's columns. `email` first, so the file imports back as it is.
ROSTER_COLUMNS = [
    "email", "status", "uid",
    *(apps.field("deviceIdLock", app) for app in apps.ALL),
    "leaseExpiresAt", "lastHeartbeatAt", "createdAt", "updatedAt",
]


def roster_csv(seats: list[dict], invites: list[dict]) -> str:
    """The roster as IT sees it in the console: every seat, then every invite."""
    rows = [[seat.get(column) for column in ROSTER_COLUMNS] for seat in seats]
    rows += [
        [invite.get("email"), "invited", "", *([""] * len(apps.ALL)),
         "", "", invite.get("createdAt"), ""]
        for invite in invites
    ]
    return _render(ROSTER_COLUMNS, rows)


AUDIT_COLUMNS = ["ts", "action", "outcome", "actorUid", "targetType", "targetId", "detail"]


def audit_csv(events: list[dict]) -> str:
    """Audit events, one a line; `detail` as compact JSON, keys sorted."""
    return _render(AUDIT_COLUMNS, [
        [e.get("ts"), e.get("action"), e.get("outcome"), e.get("uid"),
         (e.get("target") or {}).get("type"), (e.get("target") or {}).get("id"),
         json.dumps(e.get("detail") or {}, sort_keys=True, separators=(",", ":"), default=str)]
        for e in events
    ])


# ---------------- the audit export's date range ----------------

def _instant(raw: str, *, end: bool) -> datetime | None:
    """`raw` as a UTC instant. A bare date is that day's start, or for the end
    of a range the next day's start, so `until=2026-10-18` includes the 18th."""
    raw = (raw or "").strip()
    if not raw:
        return None
    try:
        if len(raw) == 10:
            day = date.fromisoformat(raw)
            if end:
                day += timedelta(days=1)
            return datetime.combine(day, time.min, tzinfo=timezone.utc)
        value = datetime.fromisoformat(raw.replace("Z", "+00:00"))
    except ValueError as e:
        raise RosterRefused(errors.INVALID_AUDIT_RANGE) from e
    return as_utc(value) if value.tzinfo else value.replace(tzinfo=timezone.utc)


def audit_range(since: str, until: str) -> tuple[datetime | None, datetime | None]:
    """The `[since, until)` an audit export asked for, or raise `RosterRefused`."""
    start, end = _instant(since, end=False), _instant(until, end=True)
    if start and end and end <= start:
        raise RosterRefused(errors.INVALID_AUDIT_RANGE)
    return start, end


# ---------------- the seat summary ----------------

def seat_summary(lic: dict, report: dict, invites: list[dict], now: datetime) -> dict:
    """What the roster amounts to, counted from stored data only.

    `report` is `repo.reconcile_institution_seats`: every seat beside its
    holder's account, which is where "active lately" and "revocation pending"
    come from. A count the stored data cannot support is left out rather
    than estimated: there is no per-seat expiry (a licence ends for every
    seat at once, so `expiring` is all assigned seats or none), and no record
    of a hold reaching the device (a hold is enforced by the backend on the
    holder's next request, so it has nothing to be pending on).
    """
    seats = report.get("seats") or []
    live = [s for s in seats if s.get("bucket") == "active"]
    cutoff = now - ACTIVE_WINDOW
    floating = normalize_seating(lic.get("seating")) == SEATING_FLOATING
    expires_at = as_utc(lic.get("expiresAt"))
    expiring = bool(expires_at and now <= expires_at <= now + EXPIRING_WINDOW)
    pending = [s for s in seats if s.get("bucket") == "revokedStillRunning"]
    counts = {
        # Holding a place on the roster: active or on hold, not removed.
        "assigned": len(live),
        "onHold": sum(1 for s in live if s.get("status") == "disabled"),
        # Of those, whose account has made any request in the last 7 days.
        "activeLast7Days": sum(
            1 for s in live
            if s.get("status") != "disabled"
            and (seen := as_utc(s.get("lastSeenAt"))) is not None and seen >= cutoff
        ),
        "invited": len(invites),
        # Removed, and the device has not been back to hear it (or the
        # demotion did not land, which removing again repairs).
        "revocationPending": len(pending),
        "revocationPendingNoCheckIn": sum(
            1 for s in pending if s.get("reason") == NO_CHECKIN_SINCE_REVOKE),
        "removed": sum(1 for s in seats if s.get("bucket") != "active"),
        "expiringWithin30Days": len(live) if expiring else 0,
    }
    return {
        "seating": normalize_seating(lic.get("seating")),
        "maxSeats": report.get("maxSeats"),
        "seatsUsed": int(lic.get("seatsUsed") or 0),
        **({"leasesActive": int(lic.get("leasesActive") or 0)} if floating else {}),
        "expiresAt": iso(lic.get("expiresAt")) or None,
        "counts": counts,
    }


def roster_addresses(seats: list[dict], invites: list[dict]) -> set[str]:
    """Every address already on the roster: a seat that holds its place
    (active or on hold) or an outstanding invite. Re-importing one of these
    is answered `exists` without touching it; a removed seat is not here,
    because adding it again is how a removed member comes back."""
    held = {normalize_email(s.get("email")) for s in seats if s.get("status") != "revoked"}
    return (held | {normalize_email(i.get("email")) for i in invites}) - {""}
