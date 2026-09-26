"""Licence administration by Semper staff: listing, renewal fan-out, whole-key revoke.
"""
from .. import errors
from ..config import settings
from ..licenses import (
    DURATION_PERPETUAL,
    DURATION_TIMED,
    KIND_INSTITUTION,
    LIVE_STATUSES,
    MODE_DEMO,
    MODE_LICENSED,
    SEATING_FLOATING,
    STATUS_REVOKED,
    as_utc,
    key_prefix,
    normalize_email,
    normalize_kind,
    normalize_seating,
    seat_cap_below_roster,
)

from . import _base
from ._base import (
    _cursor_page,
    db,
    _get_all,
    _lease_clear_patch,
    _license_mode,
    _mode_patch,
    _now,
    _seat_lease_counted,
    _update_refs,
)
from .claims import (
    _license_mirror_patch,
)
from .invites import (
    _delete_license_invites,
)
from .mint import (
    _license_public,
)


#: Most licences a search answers with. A search is an exact match on an
#: address, a domain or a key prefix, so more than this is not a search.
SEARCH_LIMIT = 50


def list_licenses(limit: int = 50, page_token: str | None = None, *,
                  include_demo: bool = False, include_revoked: bool = True,
                  q: str = "") -> tuple[list, str | None]:
    """The staff desk's licence table, newest first.

    It used to be the whole collection in document-id order. The id is the
    key's hash, so the order was random, and every account's system Demo key
    was in it: the licences anyone sold were spread thinly across page after
    page, and the desk hid Demo rows only after fetching them. Demo keys and
    revoked licences are now left out by the query, not the browser.

    `q` is a search instead of a page: licences whose `emailLock` or
    `domainLock` is `q`, or whose key starts with it, with the same filters.
    """
    col = db().collection("licenses")
    if q:
        return _search_licenses(col, q, include_demo, include_revoked), None
    query = col
    if not include_demo:
        query = query.where("mode", "==", MODE_LICENSED)
    if not include_revoked:
        query = query.where("status", "in", list(LIVE_STATUSES))
    docs, next_token = _cursor_page(col, query, limit, page_token,
                                    order_field="createdAt", descending=True)
    return [_license_public(d.id, d.to_dict() or {}) for d in docs], next_token


def _search_licenses(col, q: str, include_demo: bool, include_revoked: bool) -> list:
    """Exact-match lookups on the fields support is asked about.

    Single-field equality queries, so no composite index; the filters and the
    order are applied here, over at most `3 * SEARCH_LIMIT` rows.
    """
    text = q.strip()
    lookups = [("emailLock", normalize_email(text)), ("domainLock", text.lower())]
    if text.upper().startswith("SEMP"):
        lookups.append(("keyPrefix", key_prefix(text)))
    found = {}
    for field, value in lookups:
        if not value:
            continue
        for doc in col.where(field, "==", value).limit(SEARCH_LIMIT).stream():
            found[doc.id] = doc.to_dict() or {}
    rows = [
        (doc_id, lic) for doc_id, lic in found.items()
        if (include_demo or _license_mode(lic) != MODE_DEMO)
        and (include_revoked or (lic.get("status") or "") != STATUS_REVOKED)
    ]
    # Newest first, like the table; a licence with no `createdAt` goes last.
    rows.sort(key=lambda row: str(row[1].get("createdAt") or ""), reverse=True)
    return [_license_public(doc_id, lic) for doc_id, lic in rows[:SEARCH_LIMIT]]


def get_license_public(license_id: str) -> dict | None:
    """One licence as the desk shows it, for refreshing a single row."""
    snap = db().collection("licenses").document(license_id).get()
    return _license_public(license_id, snap.to_dict() or {}) if snap.exists else None


def revoke_license(license_id: str, admin_uid: str) -> dict | None:
    """Whole-key revoke. Every redeemer (individual redeemer, or every
    institution seat holder) drops to Demo and every occupied seat is freed.

    Leases go with the seats. They used to survive the revoke: each seat kept
    its `leaseExpiresAt`, the licence kept its `leasesActive`, and the console
    showed a revoked pool with seats in use until every lease ran out.

    Safe to run again, and running it again is the repair for one that failed
    part-way: the holders are re-checked, but a seat or licence already
    revoked keeps the `revokedAt` it had. Re-stamping it moved IT's earlier
    seat revokes to now, and reconciliation dates a revoke from that field.
    Holders are read in one call and written in batches, not one round trip
    each."""
    ref = db().collection("licenses").document(license_id)
    snap = ref.get()
    if not snap.exists:
        return None
    lic = snap.to_dict() or {}
    if (lic.get("status") or "") != STATUS_REVOKED:
        ref.update({
            "status": STATUS_REVOKED,
            "revokedAt": _base.firestore.SERVER_TIMESTAMP,
            "revokedByUid": admin_uid,
        })
    if normalize_kind(lic.get("kind")) == KIND_INSTITUTION:
        seats = list(ref.collection("seats").stream())
        # Every seat's holder, revoked seats included: a seat revoke whose
        # demotion never landed (reconcile's `still_licensed`) is repaired here.
        _drop_users_to_demo_if_licensed(
            [(s.to_dict() or {}).get("uid") or s.id for s in seats], license_id,
        )
        seat_patch = {
            "status": STATUS_REVOKED,
            "revokedAt": _base.firestore.SERVER_TIMESTAMP,
            **_lease_clear_patch(),
            "updatedAt": _base.firestore.SERVER_TIMESTAMP,
        }
        _update_refs([
            (s.reference, seat_patch) for s in seats
            if (s.to_dict() or {}).get("status") != STATUS_REVOKED
        ])
        ref.update({"seatsUsed": 0, "leasesActive": 0})
        # Answer with the counts just written. The desk merges this reply
        # into its row, and the pre-revoke snapshot showed a revoked licence
        # with every seat still taken until the list reloaded.
        lic = {**lic, "seatsUsed": 0, "leasesActive": 0}
        _delete_license_invites(license_id)
    else:
        redeemer = lic.get("redeemedByUid")
        if redeemer and _license_mode(lic) == MODE_LICENSED:
            _drop_user_to_demo_if_licensed(redeemer, license_id)
        _delete_license_invites(license_id, lic.get("emailLock") or "")
    return _license_public(license_id, {**lic, "status": STATUS_REVOKED})


class LicenseTermsRejected(Exception):
    """A licence edit `update_license` refuses. `code` is the 422 detail."""

    def __init__(self, code: str):
        super().__init__(code)
        self.code = code


def expiry_change_error(lic: dict, expires_at, *, allow_shorten: bool = False) -> str:
    """Why this new `expiresAt` may not be applied to `lic`, or "".

    The edit route is the desk's Extend button, and every holder follows the
    licence the moment it is written. A date already past ended the licence
    for all of them; one earlier than the current expiry shortened it; and
    on a perpetual licence it turned an unending licence into a timed one
    with no grace, because perpetual licences are minted without
    `graceDays`. Each of those was then reported on the desk as "extended".
    Ending a licence now is `revoke`.

    `allow_shorten` is the desk saying, with a typed confirmation, that the
    shorter term or the end date on a perpetual licence is meant: a downgrade
    agreed with the customer. A date already past is refused even then.
    """
    new = as_utc(expires_at)
    if new is None:
        return ""
    if new <= _now():
        return errors.EXPIRY_IN_PAST
    if allow_shorten:
        return ""
    current = as_utc(lic.get("expiresAt"))
    if current is None:
        return errors.LICENSE_PERPETUAL
    if new < current:
        return errors.EXPIRY_BEFORE_CURRENT
    return ""


def analysis_cap_error(lic: dict) -> str:
    """Why a new `maxAnalyses` may not be set on `lic`, or "".

    A demo holder's allowance is `DEMO_MAX_ANALYSES` and nothing on the key
    moves it (`resolve_user_config` reads the cap only for a licensed
    account). A cap written to a demo key was stored, mirrored onto the
    holder and answered 200, and changed nothing the holder could see.
    Clearing one is still allowed: it only removes a number that never
    applied.
    """
    if _license_mode(lic) == MODE_DEMO:
        return errors.CAP_ON_DEMO_KEY
    return ""


_ROSTER_FIELDS = ("maxSeats", "seating", "adminEmails")


def license_edit_error(lic: dict, patch: dict) -> str:
    """Why this edit may not be applied to `lic`, or "". Every refusal an
    edit can meet, decided before anything is written.

    `patch` is the request as sent: `allowShorten` rides along with the
    fields it qualifies.
    """
    institution = normalize_kind(lic.get("kind")) == KIND_INSTITUTION
    if not institution and any(patch.get(f) is not None for f in _ROSTER_FIELDS):
        return errors.INSTITUTION_ONLY
    if patch.get("maxAnalyses") is not None:
        err = analysis_cap_error(lic)
        if err:
            return err
    err = expiry_change_error(lic, patch.get("expiresAt"),
                              allow_shorten=bool(patch.get("allowShorten")))
    if err:
        return err
    if institution and (patch.get("maxSeats") is not None or patch.get("seating")):
        after = {**lic, **{f: patch[f] for f in ("maxSeats", "seating") if patch.get(f) is not None}}
        if normalize_seating(after.get("seating")) == SEATING_FLOATING and after.get("maxSeats") is None:
            return errors.FLOATING_NEEDS_MAX_SEATS
        # Switching to assigned makes `maxSeats` cap the roster, so it has to
        # hold everyone already on it — the same rule as lowering it.
        if seat_cap_below_roster(after, after.get("maxSeats")):
            return errors.MAX_SEATS_BELOW_USED
    return ""


def update_license(license_id: str, patch: dict, admin_uid: str) -> dict | None:
    """Change a license's terms and push them to everyone already holding it.

    Renewal is the reason this exists. `_license_mirror_patch` snapshots the
    terms onto each user at activation so the hot read path needs no Firestore
    lookup; the cost is that editing the license alone reaches nobody. So this
    writes the document and then fans the new mirror out — to the individual
    redeemer, or to every seat on an institution license.

    The fan-out is bounded by `seatsUsed` and renewal is rare, which is what
    makes this the right side of the trade against a per-request read. Revoked
    seats are skipped: they hold no entitlement to refresh, and touching them
    would quietly resurrect a revoked member on the next resolve.

    `perpetual` drops the expiry and grace. An expiry on a perpetual licence
    (with `allowShorten`) makes it timed, with the fleet default grace unless
    one is sent. A seating switch zeroes `leasesActive`; leaving floating also
    clears every lease, on the seats and on the holders, since an assigned
    seat needs none.

    Returns the updated public license, or None if there is no such license.
    Raises `LicenseTermsRejected`, before writing anything, for anything
    `license_edit_error` refuses.
    """
    ref = db().collection("licenses").document(license_id)
    snap = ref.get()
    if not snap.exists:
        return None
    lic = snap.to_dict() or {}

    update = {k: v for k, v in patch.items() if v is not None}
    err = license_edit_error(lic, update)
    if err:
        raise LicenseTermsRejected(err)
    allow_shorten = update.pop("allowShorten", False)
    clear_cap = bool(update.pop("clearMaxAnalyses", False))
    if clear_cap:
        update["maxAnalyses"] = _base.firestore.DELETE_FIELD
    if update.pop("perpetual", False):
        update.update({
            "expiresAt": _base.firestore.DELETE_FIELD,
            "graceDays": _base.firestore.DELETE_FIELD,
            "duration": DURATION_PERPETUAL,
        })
    elif "expiresAt" in update:
        update["duration"] = DURATION_TIMED
        if allow_shorten and lic.get("graceDays") is None and "graceDays" not in update:
            # A perpetual licence was minted without a grace; one given an
            # end date gets the fleet default, as a timed mint does.
            update["graceDays"] = settings.LICENSE_GRACE_DAYS_DEFAULT
    old_seating = normalize_seating(lic.get("seating"))
    if "seating" in update:
        update["seating"] = normalize_seating(update["seating"])
        if update["seating"] == old_seating:
            del update["seating"]
        else:
            update["leasesActive"] = 0
    if not update:
        return _license_public(license_id, lic)
    if "graceDays" in update and update["graceDays"] is not _base.firestore.DELETE_FIELD:
        update["graceDays"] = max(0, int(update["graceDays"]))
    update["updatedAt"] = _base.firestore.SERVER_TIMESTAMP
    update["updatedByUid"] = admin_uid
    ref.update(update)

    # The sentinels are for Firestore; the mirror and the response read the
    # licence as it now stands.
    merged = {k: v for k, v in {**lic, **update}.items() if v is not _base.firestore.DELETE_FIELD}
    left_floating = old_seating == SEATING_FLOATING and "seating" in update
    if left_floating:
        _clear_seat_leases(ref)
    mirror = _license_mirror_patch(merged)
    # Only the terms mirrored onto holders need to reach them. A note, a
    # support date or a seat cap changes nothing on any user document, and
    # used to rewrite every one of them anyway.
    if mirror != _license_mirror_patch(lic):
        extra = {"leaseExpiresAt": _base.firestore.DELETE_FIELD} if left_floating else {}
        _refresh_license_mirrors(_license_holder_uids(ref, merged), license_id,
                                 {**mirror, **extra})
    return _license_public(license_id, merged)


def _clear_seat_leases(ref) -> None:
    """Drop every lease on a licence that no longer uses them."""
    _update_refs([
        (doc.reference, {**_lease_clear_patch(), "updatedAt": _base.firestore.SERVER_TIMESTAMP})
        for doc in ref.collection("seats").stream()
        if _seat_lease_counted(doc.to_dict() or {})
    ])


def _license_holder_uids(ref, lic: dict) -> list[str]:
    """Everyone currently entitled by this license."""
    if normalize_kind(lic.get("kind")) != KIND_INSTITUTION:
        redeemer = lic.get("redeemedByUid")
        return [redeemer] if redeemer else []
    out = []
    for seat_doc in ref.collection("seats").stream():
        seat = seat_doc.to_dict() or {}
        if seat.get("status") == STATUS_REVOKED:
            continue
        out.append(seat.get("uid") or seat_doc.id)
    return out


def _users_on_license(uids: list[str], license_id: str) -> list:
    """References to those of `uids` whose account still points at this licence.

    One `get_all` for the lot. The guard is the one every writer here uses: a
    holder who has since moved to another licence must not have this one's
    terms, or its revoke, written over theirs.
    """
    users = db().collection("users")
    refs = [users.document(uid) for uid in dict.fromkeys(u for u in uids if u)]
    return [
        snap.reference for snap in _get_all(refs)
        if snap.exists and (snap.to_dict() or {}).get("licenseId") == license_id
    ]


def _refresh_license_mirrors(uids: list[str], license_id: str, mirror: dict) -> None:
    """Re-stamp every holder's copy of the license terms, in batches."""
    patch = {**mirror, "updatedAt": _base.firestore.SERVER_TIMESTAMP}
    _update_refs([(ref, patch) for ref in _users_on_license(uids, license_id)])


def _demo_drop_patch() -> dict:
    return {
        **_mode_patch(MODE_DEMO),
        "leaseExpiresAt": _base.firestore.DELETE_FIELD,
        "updatedAt": _base.firestore.SERVER_TIMESTAMP,
        # The instant from which "has this account been back since?" is
        # asked. `_touch_user` throttles lastSeenAt to the hour, which
        # would leave reconciliation reading a landed revoke as unlanded
        # for that long; the checkpoint makes the account's next request
        # bypass the throttle exactly once. Written inside the update the
        # demotion already performs, so it costs no extra write here.
        "seenCheckpointAt": _base.firestore.SERVER_TIMESTAMP,
    }


def _drop_users_to_demo_if_licensed(uids: list[str], license_id: str) -> None:
    """`_drop_user_to_demo_if_licensed` for a whole roster: one read call,
    batched writes."""
    patch = _demo_drop_patch()
    _update_refs([(ref, patch) for ref in _users_on_license(uids, license_id)])


def _drop_user_to_demo_if_licensed(uid: str, license_id: str) -> None:
    """Drop a user to Demo only if they are still pointed at this exact
    license — activation is in-place (same uid/doc, no data migration), and
    downgrade must never delete or hide existing sessions/files, only stop
    new analysis creation once the account is back over the Demo cap.

    The holder's copy of a floating lease goes too. Every caller has just
    taken the seat's lease away (revoke, hold, whole-licence revoke), and the
    copy left behind read as a seat still in use on the account page."""
    user_ref = db().collection("users").document(uid)
    user_snap = user_ref.get()
    if user_snap.exists and (user_snap.to_dict() or {}).get("licenseId") == license_id:
        user_ref.update(_demo_drop_patch())
