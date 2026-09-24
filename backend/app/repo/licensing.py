"""Licence keys: minting, claims, invites, entitlement at sign-in, and licence administration.
"""
import logging

from .. import statuses
from ..config import settings
from ..licenses import (
    DURATION_PERPETUAL,
    DURATION_TIMED,
    SEATING_ASSIGNED,
    SEATING_FLOATING,
    KIND_INDIVIDUAL,
    KIND_INSTITUTION,
    MODE_DEMO,
    MODE_LICENSED,
    generate_key,
    grace_ends_at,
    invite_id,
    key_hash,
    key_prefix,
    legacy_plan,
    normalize_duration,
    normalize_email,
    normalize_kind,
    normalize_seating,
)

from . import _base
from ._base import (
    _CONTENDED,
    _cursor_page,
    db,
    get_license,
    _license_mode,
    _license_past_grace,
    _load_user,
    _mode_patch,
    _run_tx,
    SCHEMA_VERSION,
    _seat_ref,
)
from .devlock import (
    bind_device_lock,
)
from .user_config import (
    resolve_user_config,
)


log = logging.getLogger("indic.firestore")


def _public_claim_error(err: str, fallback: str) -> str:
    """The wire code for a claim failure, contention included.

    Contention fails closed as `fallback` — granting a seat or a licence we
    could not commit is the one outcome that breaks the cap, and a caller who
    lost the race succeeds on their next request.
    """
    return fallback if err == _CONTENDED else err


def _emails_match(left, right) -> bool:
    a = (left or "").strip().lower()
    b = (right or "").strip().lower()
    return bool(a and b and a == b)


def _email_domain(email: str) -> str:
    email = (email or "").strip().lower()
    return email.rsplit("@", 1)[-1] if "@" in email else ""


def _license_public(license_id: str, data: dict) -> dict:
    return {
        "id": license_id,
        "keyPrefix": data.get("keyPrefix") or "",
        "kind": normalize_kind(data.get("kind")),
        "mode": _license_mode(data),
        # Pre-rename mirror; see resolve_user_config.
        "plan": legacy_plan(_license_mode(data)),
        "status": data.get("status") or "unused",
        "emailLock": data.get("emailLock") or "",
        "deviceIdLock": data.get("deviceIdLock") or "",
        "domainLock": data.get("domainLock") or "",
        "adminEmails": list(data.get("adminEmails") or []),
        "maxSeats": data.get("maxSeats"),
        "seating": normalize_seating(data.get("seating")),
        # seatsUsed counts the roster; leasesActive counts who is using it
        # right now. On an assigned license they are the same number, so only
        # the first is meaningful.
        "seatsUsed": data.get("seatsUsed", 0),
        "leasesActive": data.get("leasesActive", 0),
        "createdAt": data.get("createdAt"),
        "createdByUid": data.get("createdByUid") or "",
        "redeemedAt": data.get("redeemedAt"),
        "redeemedByUid": data.get("redeemedByUid") or "",
        "duration": normalize_duration(
            data.get("duration"), has_expiry=data.get("expiresAt") is not None,
        ),
        "expiresAt": data.get("expiresAt"),
        "graceDays": data.get("graceDays"),
        "graceEndsAt": grace_ends_at(data.get("expiresAt"), data.get("graceDays") or 0),
        # Informational; never gates. A perpetual license whose support has
        # lapsed still grants full use.
        "supportUntil": data.get("supportUntil"),
        "maxAnalyses": data.get("maxAnalyses"),
        "note": data.get("note") or "",
    }


def _write_license(
    *,
    mode: str,
    email_lock: str,
    device_id_lock: str,
    created_by_uid: str,
    status: str,
    kind: str = KIND_INDIVIDUAL,
    domain_lock: str = "",
    admin_emails: list[str] | None = None,
    max_seats: int | None = None,
    seating: str = SEATING_ASSIGNED,
    redeemed_by_uid: str | None = None,
    expires_at=None,
    grace_days: int | None = None,
    support_until=None,
    max_analyses: int | None = None,
    note: str = "",
) -> tuple[str, str, dict]:
    """Mint a key, persist the hash, return (plaintext, license_id, stored)."""
    key = generate_key()
    license_id = key_hash(key)
    stored = {
        "keyPrefix": key_prefix(key),
        # Redundant with the document id, which is this same hash. Recorded as
        # a field so licenses can later move to opaque ids — needed once a
        # license may be issued with no key at all.
        "keyHash": license_id,
        "kind": kind,
        "mode": mode,
        # Pre-rename mirror; see _mode_patch.
        "plan": legacy_plan(mode),
        "status": status,
        "emailLock": (email_lock or "").strip().lower(),
        "deviceIdLock": device_id_lock,
        "createdAt": _base.firestore.SERVER_TIMESTAMP,
        "createdByUid": created_by_uid,
        "schemaVersion": SCHEMA_VERSION,
        "note": note or "",
    }
    if kind == KIND_INSTITUTION:
        stored["domainLock"] = (domain_lock or "").strip().lower()
        stored["adminEmails"] = [
            (e or "").strip().lower() for e in (admin_emails or []) if (e or "").strip()
        ]
        if max_seats is not None:
            stored["maxSeats"] = int(max_seats)
        stored["seatsUsed"] = 0
        stored["seating"] = normalize_seating(seating)
        stored["leasesActive"] = 0
    if expires_at is not None:
        stored["expiresAt"] = expires_at
        stored["duration"] = DURATION_TIMED
        # Stamped explicitly at mint so the value in force is recorded on the
        # document rather than inherited from whatever the env says later.
        stored["graceDays"] = (
            settings.LICENSE_GRACE_DAYS_DEFAULT if grace_days is None else max(0, int(grace_days))
        )
    else:
        stored["duration"] = DURATION_PERPETUAL
    if support_until is not None:
        # Informational only. A perpetual license whose support has lapsed
        # still grants full use — nothing reads this to gate anything.
        stored["supportUntil"] = support_until
    if max_analyses is not None:
        stored["maxAnalyses"] = int(max_analyses)
    if redeemed_by_uid:
        stored["redeemedByUid"] = redeemed_by_uid
        stored["redeemedAt"] = _base.firestore.SERVER_TIMESTAMP
    ref = db().collection("licenses").document(license_id)
    ref.set(stored)
    # Read back rather than return what was written: `createdAt` (and
    # `redeemedAt`) above are the SERVER_TIMESTAMP sentinel, and the mint
    # routes hand this dict to the response, where the sentinel cannot be
    # serialised — the licence was written and the request still failed.
    return key, license_id, (ref.get().to_dict() or stored)


def ensure_demo_license(user: dict, device_id: str | None) -> dict:
    """Issue a redeemed Demo key once the account is approved, verified, and bound.

    Idempotent. Licensed accounts are left alone. The plaintext Demo key is
    not returned — the user never types it; the record exists so the seat is
    locked to this email and device.

    The attachment is a compare-and-set on the stored document, not a blind
    update. `user` was read before this request began, and several requests
    arrive together at app launch: one that lost the race to claim a real
    licence — the delivery path for every individual licence and every invited
    seat — falls through to here holding a stale copy, and a blind write would
    overwrite the entitlement granted moments earlier with a Demo key.
    """
    uid = user.get("uid")
    if not uid or user.get("licenseId"):
        return user
    if user.get("access_status") != statuses.ACCESS_APPROVED:
        return user
    if not user.get("emailVerified"):
        return user
    email = (user.get("email") or "").strip().lower()
    device = device_id or user.get("activeDeviceId") or user.get("claimedDeviceId")
    if not email or not device:
        return user
    _key, license_id, stored = _write_license(
        mode=MODE_DEMO,
        email_lock=email,
        device_id_lock=device,
        created_by_uid="system",
        status="redeemed",
        redeemed_by_uid=uid,
    )
    patch = {
        **_mode_patch(MODE_DEMO),
        "licenseId": license_id,
        "licenseKind": KIND_INDIVIDUAL,
        "licensePrefix": stored["keyPrefix"],
        "updatedAt": _base.firestore.SERVER_TIMESTAMP,
    }
    user_ref = db().collection("users").document(uid)

    @_base.firestore.transactional
    def _attach(tx) -> bool:
        snap = user_ref.get(transaction=tx)
        if not snap.exists or ((snap.to_dict() or {}).get("licenseId") or ""):
            return False
        tx.update(user_ref, patch)
        return True

    attached = _run_tx(_attach, on_contended=lambda: False)

    if attached:
        return {**user, **patch}
    # Something reached this account first. Drop the key nobody will ever hold
    # rather than leaving a redeemed Demo record pointing at no one, and answer
    # with what the account actually has — which on the losing side of an
    # invite claim is the licence, not demo.
    db().collection("licenses").document(license_id).delete()
    current = user_ref.get()
    if not current.exists:
        return user
    return {**user, **(current.to_dict() or {}), "uid": uid}


def create_individual_license(
    *,
    email_lock: str,
    device_id_lock: str = "",
    created_by_uid: str,
    expires_at=None,
    max_analyses: int | None = None,
    note: str = "",
) -> dict:
    """Ops mint of an individual licensed key. Returns the plaintext key
    once; only the hash is stored.

    Also records a pending invite against `email_lock`, which is how the
    licence actually reaches the customer: they sign in with that address and
    `claim_pending_invite` attaches the licence on their first request. The
    key is the fallback for support recovery, not the delivery mechanism —
    nobody should have to type one.

    `device_id_lock` stays available for the rare mint against a device we
    already know, but is empty in normal use; the lock is bound at first
    sign-in instead.
    """
    key, license_id, stored = _write_license(
        mode=MODE_LICENSED,
        email_lock=email_lock,
        device_id_lock=device_id_lock,
        created_by_uid=created_by_uid,
        status="unused",
        kind="individual",
        expires_at=expires_at,
        max_analyses=max_analyses,
        note=note,
    )
    err, _ = _write_invite(license_id, email_lock, created_by_uid)
    if err:
        # The licence exists and the key in hand still redeems it, so this is
        # degraded delivery rather than a failed mint. `invite_exists` means
        # the address is already promised another licence — a real conflict
        # for ops to resolve, and one the returned licence makes visible.
        log.warning("individual licence %s minted without an invite: %s", license_id, err)
        claimed_uid, claim_err = "", ""
    else:
        claimed_uid, claim_err = _attach_to_existing_holder(license_id, stored, email_lock)
    return {"key": key, "license": _license_public(license_id, stored),
            "inviteError": err or "", "claimedByUid": claimed_uid,
            "claimError": claim_err}


def _holds_only_a_demo_key(user: dict) -> bool:
    """True when this account can take a licence right now: it points at no
    licence, at the auto-minted Demo key, or at one that is revoked or gone.

    The discriminator is the licence document, as in `_drop_superseded_demo`:
    a revoked holder is left demoted in place and still pointing at the real
    licence, so its stored `mode` says nothing about whether a new grant is
    welcome. A *live* non-demo licence is the one thing that must not be
    overwritten — that is a support question, not a mint."""
    license_id = user.get("licenseId") or ""
    if not license_id:
        return True
    lic = get_license(license_id)
    if not lic or (lic.get("status") or "") == "revoked":
        return True
    return _license_mode(lic) == MODE_DEMO and (lic.get("createdByUid") or "") == "system"


def _attach_to_existing_holder(license_id: str, lic: dict, email: str) -> tuple[str, str]:
    """Hand a freshly minted individual licence to the account that already
    signed in at its address. Returns (uid, error) — both empty when nobody
    holds the address yet and the invite alone will deliver.

    The counterpart of `add_institution_member`'s existing-account branch, and
    needed for the same reason: `claim_pending_invite` runs only for an
    account holding no licence, and `ensure_demo_license` stamps one on the
    first request an approved account makes. Every account that predates
    licensing therefore holds a Demo key by the time ops mints for it, and an
    invite left for it would never be read. The claim is the same transaction
    the sign-in path uses (`claim_individual_license`), which consumes the
    invite and drops the superseded Demo key.

    Fail closed on an unverified address or an unapproved account: the address
    is the whole claim to the licence, exactly as at sign-in, and neither of
    those accounts holds a Demo key yet — so for them the invite still works
    the moment they qualify. A holder of a live non-demo licence is left as
    they are and reported, rather than silently moved between licences.
    """
    address = normalize_email(email)
    holder = find_user_by_email(address) if address else None
    if not holder:
        return "", ""
    if holder.get("access_status") != statuses.ACCESS_APPROVED or not holder.get("emailVerified"):
        return "", ""
    if not _holds_only_a_demo_key(holder):
        return "", "holder_already_licensed"
    err = claim_individual_license(
        license_id, holder["uid"], address,
        _individual_member_patch(license_id, lic), invite_ref=_invite_ref(address),
    )
    if err:
        # A lost race here has no next request to fall back on — the holder's
        # Demo key short-circuits the sign-in path — so say so plainly rather
        # than leaking the private marker. The licence is minted and the key
        # in hand redeems it through the support route.
        public = _public_claim_error(err, "claim_contended")
        log.warning("individual licence %s not attached to %s: %s",
                    license_id, holder["uid"], public)
        return "", public
    return holder["uid"], ""


def create_institution_license(
    *,
    domain_lock: str,
    admin_emails: list[str],
    created_by_uid: str,
    max_seats: int | None = None,
    seating: str = SEATING_ASSIGNED,
    expires_at=None,
    max_analyses: int | None = None,
    note: str = "",
) -> dict:
    """Ops mint of an institution key. Seats are granted individually via
    activate_license as members of `domain_lock` redeem the same key; ops never
    pre-allocates seats. Returns the plaintext key once."""
    key, license_id, stored = _write_license(
        mode=MODE_LICENSED,
        email_lock="",
        device_id_lock="",
        created_by_uid=created_by_uid,
        status="active",
        kind=KIND_INSTITUTION,
        domain_lock=domain_lock,
        admin_emails=admin_emails,
        max_seats=max_seats,
        seating=seating,
        expires_at=expires_at,
        max_analyses=max_analyses,
        note=note,
    )
    return {"key": key, "license": _license_public(license_id, stored)}


def list_licenses(limit: int = 50, page_token: str | None = None) -> tuple[list, str | None]:
    col = db().collection("licenses")
    docs, next_token = _cursor_page(col, col, limit, page_token)
    return [_license_public(d.id, d.to_dict() or {}) for d in docs], next_token


def _license_mirror_patch(lic: dict) -> dict:
    """The license terms copied onto the user document at activation.

    `effective_mode` and `license_summary` read only the user dict, which is
    what keeps `resolve_user_config` free of Firestore reads on request paths
    that call it for every session and file. The cost is that these are a
    snapshot: changing the license after activation does not reach anyone who
    already holds a seat. `update_license` fans the new values back out — it is
    the only writer that has to, and it is rare.

    Every field is written on every activation, cleared with DELETE_FIELD when
    the license does not carry it, so re-activating onto a different license
    never leaves a stale term behind.
    """
    expires_at = lic.get("expiresAt")
    grace_days = lic.get("graceDays")
    max_analyses = lic.get("maxAnalyses")
    return {
        "licenseExpiresAt": expires_at if expires_at is not None else _base.firestore.DELETE_FIELD,
        "licenseGraceDays": (
            max(0, int(grace_days)) if isinstance(grace_days, (int, float))
            else _base.firestore.DELETE_FIELD
        ),
        "licenseDuration": normalize_duration(
            lic.get("duration"), has_expiry=expires_at is not None,
        ),
        # Not the lease itself — only whether this license needs one. The lease
        # is written by checkout and cleared by release, and re-stamping it
        # here would hand a seat back to someone who had released it.
        "licenseSeating": normalize_seating(lic.get("seating")),
        "licenseMaxAnalyses": int(max_analyses) if max_analyses else _base.firestore.DELETE_FIELD,
    }


def _activate_individual(user: dict, uid: str, email: str, device_id: str, lic: dict, ref, key: str):
    status = lic.get("status") or "unused"
    if status == "revoked":
        return "license_revoked", None
    if not _emails_match(lic.get("emailLock"), email):
        return "license_email_mismatch", None
    locked = lic.get("deviceIdLock") or ""
    if not locked and device_id:
        # Bind-on-first-use, the same rule the request path applies. A licence
        # minted against an address alone has no lock, so a key typed here for
        # support recovery has to be able to set one rather than demand it.
        # Re-read rather than assume: bind_device_lock is first-writer-wins,
        # and losing the race means some other device owns this licence. A
        # bind starved with the lock still empty raises DeviceLockContended
        # (503) instead, so this never answers a mismatch nobody holds.
        bind_device_lock(ref, device_id)
        locked = (ref.get().to_dict() or {}).get("deviceIdLock") or ""
    if locked != device_id:
        return "license_device_mismatch", None
    if status == "redeemed" and lic.get("redeemedByUid") != uid:
        return "license_already_redeemed", None

    mode = _license_mode(lic)
    license_id = ref.id
    user_patch = {
        **_mode_patch(mode),
        "licenseId": license_id,
        "licenseKind": KIND_INDIVIDUAL,
        "licensePrefix": lic.get("keyPrefix") or key_prefix(key),
        "updatedAt": _base.firestore.SERVER_TIMESTAMP,
    }
    user_patch.update(_license_mirror_patch(lic))

    license_patch = {}
    if status == "unused":
        license_patch = {
            "status": "redeemed",
            "redeemedByUid": uid,
            "redeemedAt": _base.firestore.SERVER_TIMESTAMP,
        }

    batch = db().batch()
    batch.update(db().collection("users").document(uid), user_patch)
    if license_patch:
        batch.update(ref, license_patch)
    batch.commit()

    merged = _apply_patch(user, user_patch)
    return "", resolve_user_config(merged)


def _drop_superseded_demo(uid: str, license_id: str) -> None:
    """Delete the auto-minted Demo key an account has just stopped pointing at.

    `ensure_demo_license` is a compare-and-set, so a request that lost the
    race can no longer stamp Demo *over* a real licence. The opposite order is
    what is left: the loser commits its Demo key first, the winning claim then
    moves the account's pointer, and the Demo record survives
    `status: "redeemed"` with nobody holding it — indistinguishable in
    `GET /v1/admin/licenses` from a live key, one more of them for every raced
    sign-in.

    This runs after a claim commits, deliberately not inside it. Reading
    `users/{uid}` in the claim's transaction looked like the tidy answer, and
    it is wrong: a transaction that reads and then writes one document locks
    it, so several requests arriving at one account together — the shape of
    every app launch, and of the invite delivery path — abort each other
    instead of queueing. Measured against the emulator, six concurrent
    sign-ins starved out completely and the account landed on Demo. A plain
    query and a guarded delete take no locks, and the worst a lost race costs
    here is that the record survives to the next claim: the condition being
    closed is litter in the operator listing, never a wrong entitlement.

    The discriminator is the licence document, never the holder's `mode`
    mirror. `_drop_user_to_demo_if_licensed` leaves a revoked holder demoted
    in place and still pointing at the real, revoked licence, so a mirror test
    would delete revocation records — considerably worse than the leak this
    closes. Only `ensure_demo_license` writes `mode: demo` and
    `createdByUid: "system"` together.
    """
    snap = db().collection("users").document(uid).get()
    if not snap.exists or ((snap.to_dict() or {}).get("licenseId") or "") != license_id:
        # Something has moved the account on again. Whatever it holds now is
        # not this claim's to reason about.
        return
    for doc in db().collection("licenses").where("redeemedByUid", "==", uid).stream():
        if doc.id == license_id:
            continue
        lic = doc.to_dict() or {}
        if _license_mode(lic) == MODE_DEMO and (lic.get("createdByUid") or "") == "system":
            doc.reference.delete()


def claim_seat(license_id: str, uid: str, email: str, device_id: str, user_patch: dict,
               invite_ref=None) -> str:
    """Take a seat on the roster, atomically. Returns an error code, or "".

    This was a read-then-`WriteBatch` — atomic for its writes, but carrying no
    reads and no preconditions, so two members activating at once on a pool of
    ten both saw nine free and the count landed at eleven. A batch is not a
    transaction. With a floating pool the count is the actual boundary rather
    than a soft allocation, so the whole claim now reads and writes under one.

    Every read happens before every write, as Firestore requires. The seat
    validation lives inside for the same reason as the count: it is decided
    from data read in the transaction.

    `invite_ref`, when given, is a pending invite being redeemed: it is read
    with the other reads and deleted with the other writes, so the seat and
    the invite settle together. Consuming it in a second write would leave a
    window where IT has revoked the invite but the seat is granted anyway, or
    where the invite is gone and the claim then fails — either way the roster
    and the invite list disagree.
    """
    lic_ref = db().collection("licenses").document(license_id)
    seat_ref = _seat_ref(license_id, uid)
    user_ref = db().collection("users").document(uid)

    @_base.firestore.transactional
    def _claim(tx) -> str:
        lic_snap = lic_ref.get(transaction=tx)
        seat_snap = seat_ref.get(transaction=tx)
        invite_snap = invite_ref.get(transaction=tx) if invite_ref is not None else None
        if not lic_snap.exists:
            return "license_not_found"
        if invite_ref is not None and not invite_snap.exists:
            # Revoked between the read that found it and this transaction.
            return "invite_not_found"
        lic = lic_snap.to_dict() or {}
        if (lic.get("status") or "active") == "revoked":
            return "license_revoked"

        seat = seat_snap.to_dict() if seat_snap.exists else None
        if seat and seat.get("status") == "revoked":
            # revoke_institution_seat() already freed this slot. A revoked seat
            # is not a permanent ban — the holder may be re-admitted and takes
            # a fresh slot through the normal maxSeats check below.
            seat = None

        if seat:
            if seat.get("status") == "disabled":
                return "license_seat_disabled"
            locked_device = seat.get("deviceIdLock") or ""
            if device_id and locked_device and locked_device != device_id:
                return "license_device_mismatch"
            seat_patch = {"updatedAt": _base.firestore.SERVER_TIMESTAMP}
            if device_id:
                # Only when a device actually redeemed. IT adding a member
                # passes no device, and must not wipe the lock of someone who
                # already has one.
                seat_patch["deviceIdLock"] = device_id
            tx.update(seat_ref, seat_patch)
        else:
            # `maxSeats` caps the ROSTER on an assigned license, where holding
            # a seat is holding the entitlement. On a floating one it caps
            # concurrent LEASES instead — a fifty-person lab sharing ten slots
            # is the whole point, so the roster is deliberately uncapped and
            # the check moves to checkout_lease.
            max_seats = lic.get("maxSeats")
            seats_used = int(lic.get("seatsUsed") or 0)
            floating = normalize_seating(lic.get("seating")) == SEATING_FLOATING
            if not floating and max_seats is not None and seats_used >= int(max_seats):
                return "license_seats_exhausted"
            tx.set(seat_ref, {
                "uid": uid,
                "email": (email or "").strip().lower(),
                "deviceIdLock": device_id,
                "status": "active",
                "createdAt": _base.firestore.SERVER_TIMESTAMP,
                "updatedAt": _base.firestore.SERVER_TIMESTAMP,
            })
            tx.update(lic_ref, {"seatsUsed": _base.firestore.Increment(1)})
        tx.update(user_ref, user_patch)
        if invite_ref is not None:
            tx.delete(invite_ref)
        return ""

    # Fail closed on contention. Handing out a seat we could not commit is the
    # one outcome that breaks the cap; a caller who lost the race just tries
    # again, and on a pool with room they win immediately.
    err = _run_tx(_claim, on_contended=lambda: _CONTENDED)
    if not err:
        _drop_superseded_demo(uid, license_id)
    return err


def _individual_member_patch(license_id: str, lic: dict) -> dict:
    """The user-document patch that attaches an individual licence.

    The counterpart to `_institution_member_patch`, and shared for the same
    reason: a licence reached by typing its key and one reached by signing in
    at the invited address must entitle the holder identically.
    """
    patch = {
        **_mode_patch(_license_mode(lic)),
        "licenseId": license_id,
        "licenseKind": KIND_INDIVIDUAL,
        "licensePrefix": lic.get("keyPrefix") or "",
        "updatedAt": _base.firestore.SERVER_TIMESTAMP,
    }
    patch.update(_license_mirror_patch(lic))
    return patch


def claim_individual_license(license_id: str, uid: str, email: str,
                             user_patch: dict, invite_ref=None) -> str:
    """Attach an individual licence to `uid`, atomically. Error code, or "".

    The individual counterpart to `claim_seat`, transactional for the same
    reason: `redeemedByUid` names one account, so two requests arriving
    together must not both come away holding the licence. Reads before
    writes, and the invite is consumed in the same transaction, so the promise
    and the grant settle together rather than leaving a window where one
    exists without the other.

    No device lock is written here. Binding happens on the request path — see
    `revalidate_device_lock` — because this runs for a caller who may not have
    presented a device at all.
    """
    lic_ref = db().collection("licenses").document(license_id)
    user_ref = db().collection("users").document(uid)

    @_base.firestore.transactional
    def _claim(tx) -> str:
        lic_snap = lic_ref.get(transaction=tx)
        invite_snap = invite_ref.get(transaction=tx) if invite_ref is not None else None
        if not lic_snap.exists:
            return "license_not_found"
        if invite_ref is not None and not invite_snap.exists:
            # Withdrawn between the read that found it and this transaction.
            return "invite_not_found"
        lic = lic_snap.to_dict() or {}
        status = lic.get("status") or "unused"
        if status == "revoked":
            return "license_revoked"
        if not _emails_match(lic.get("emailLock"), email):
            return "license_email_mismatch"
        redeemer = lic.get("redeemedByUid")
        if redeemer and redeemer != uid:
            return "license_already_redeemed"
        if status == "unused":
            tx.update(lic_ref, {
                "status": "redeemed",
                "redeemedByUid": uid,
                "redeemedAt": _base.firestore.SERVER_TIMESTAMP,
            })
        tx.update(user_ref, user_patch)
        if invite_ref is not None:
            tx.delete(invite_ref)
        return ""

    # Fail closed on contention, as claim_seat does: granting a licence we
    # could not commit is the outcome that breaks single-redeemer. The caller
    # retries on their next request, which for the invite path is moments away.
    err = _run_tx(_claim, on_contended=lambda: _CONTENDED)
    if not err:
        _drop_superseded_demo(uid, license_id)
    return err


def _activate_institution(user: dict, uid: str, email: str, device_id: str, lic: dict, ref, key: str):
    if (lic.get("status") or "active") == "revoked":
        return "license_revoked", None
    domain_lock = (lic.get("domainLock") or "").strip().lower()
    if not domain_lock or _email_domain(email) != domain_lock:
        return "license_email_mismatch", None

    license_id = ref.id
    user_patch = {
        **_mode_patch(MODE_LICENSED),
        "licenseId": license_id,
        "licenseKind": KIND_INSTITUTION,
        "licensePrefix": lic.get("keyPrefix") or key_prefix(key),
        "updatedAt": _base.firestore.SERVER_TIMESTAMP,
    }
    user_patch.update(_license_mirror_patch(lic))

    err = claim_seat(license_id, uid, email, device_id, user_patch)
    if err:
        return _public_claim_error(err, "license_seats_exhausted"), None

    merged = _apply_patch(user, user_patch)
    return "", resolve_user_config(merged)


def _apply_patch(user: dict, patch: dict) -> dict:
    merged = {**user, **{k: v for k, v in patch.items() if v is not _base.firestore.DELETE_FIELD}}
    for k, v in patch.items():
        if v is _base.firestore.DELETE_FIELD:
            merged.pop(k, None)
    return merged


def activate_license(uid: str, email: str, device_id: str, key: str) -> tuple[str, dict | None]:
    """Redeem a key onto this uid. Returns (error_code, config_or_none).

    Empty error_code means success. Branches on the license's `kind`:
    - individual: single email+device lock, same behaviour as before
      institution licensing existed. Same uid re-entering the same key is OK.
    - institution: verified-email domain match against `domainLock`; a seat is
      created (or re-validated) in `licenses/{id}/seats/{uid}`, capped at
      `maxSeats` when set. Re-entry from the same device is idempotent; from a
      different device it re-locks the seat only when no device is locked yet.
    """
    user = _load_user(uid)
    if not user:
        return "user_not_found", None
    license_id = key_hash(key)
    ref = db().collection("licenses").document(license_id)
    snap = ref.get()
    if not snap.exists:
        return "license_not_found", None
    lic = snap.to_dict() or {}
    if _license_past_grace(lic):
        # Without this the activation "succeeds": the past expiry is mirrored
        # onto the user, effective_mode immediately resolves demo, and the
        # caller is handed err="" with a demo config and no explanation.
        return "license_expired", None
    if normalize_kind(lic.get("kind")) == KIND_INSTITUTION:
        return _activate_institution(user, uid, email, device_id, lic, ref, key)
    return _activate_individual(user, uid, email, device_id, lic, ref, key)


def revoke_license(license_id: str, admin_uid: str) -> dict | None:
    """Whole-key revoke. Every redeemer (individual redeemer, or every
    institution seat holder) drops to Demo and every occupied seat is freed."""
    ref = db().collection("licenses").document(license_id)
    snap = ref.get()
    if not snap.exists:
        return None
    lic = snap.to_dict() or {}
    ref.update({
        "status": "revoked",
        "revokedAt": _base.firestore.SERVER_TIMESTAMP,
        "revokedByUid": admin_uid,
    })
    if normalize_kind(lic.get("kind")) == KIND_INSTITUTION:
        for seat_doc in ref.collection("seats").stream():
            seat = seat_doc.to_dict() or {}
            seat_uid = seat.get("uid") or seat_doc.id
            _drop_user_to_demo_if_licensed(seat_uid, license_id)
            seat_doc.reference.update({
                "status": "revoked",
                "revokedAt": _base.firestore.SERVER_TIMESTAMP,
                "updatedAt": _base.firestore.SERVER_TIMESTAMP,
            })
        ref.update({"seatsUsed": 0})
        _delete_license_invites(license_id)
    else:
        redeemer = lic.get("redeemedByUid")
        if redeemer and _license_mode(lic) == MODE_LICENSED:
            _drop_user_to_demo_if_licensed(redeemer, license_id)
        _delete_license_invites(license_id, lic.get("emailLock") or "")
    return _license_public(license_id, {**lic, "status": "revoked"})


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

    Returns the updated public license, or None if there is no such license.
    """
    ref = db().collection("licenses").document(license_id)
    snap = ref.get()
    if not snap.exists:
        return None
    lic = snap.to_dict() or {}

    update = {k: v for k, v in patch.items() if v is not None}
    if not update:
        return _license_public(license_id, lic)
    if "expiresAt" in update:
        # A license given an expiry becomes timed; the mint-time validator
        # cannot speak for an edit made years later.
        update["duration"] = DURATION_TIMED
    if "graceDays" in update:
        update["graceDays"] = max(0, int(update["graceDays"]))
    update["updatedAt"] = _base.firestore.SERVER_TIMESTAMP
    update["updatedByUid"] = admin_uid
    ref.update(update)

    merged = {**lic, **update}
    mirror = _license_mirror_patch(merged)
    for uid in _license_holder_uids(ref, merged):
        _refresh_license_mirror(uid, license_id, mirror)
    return _license_public(license_id, merged)


def _license_holder_uids(ref, lic: dict) -> list[str]:
    """Everyone currently entitled by this license."""
    if normalize_kind(lic.get("kind")) != KIND_INSTITUTION:
        redeemer = lic.get("redeemedByUid")
        return [redeemer] if redeemer else []
    out = []
    for seat_doc in ref.collection("seats").stream():
        seat = seat_doc.to_dict() or {}
        if seat.get("status") == "revoked":
            continue
        out.append(seat.get("uid") or seat_doc.id)
    return out


def _refresh_license_mirror(uid: str, license_id: str, mirror: dict) -> None:
    """Re-stamp one holder's copy of the license terms.

    Guarded on `licenseId` for the same reason as the demo drop below: a user
    who has since moved to a different license must not have this one's terms
    written over theirs.
    """
    user_ref = db().collection("users").document(uid)
    user_snap = user_ref.get()
    if not user_snap.exists:
        return
    if (user_snap.to_dict() or {}).get("licenseId") != license_id:
        return
    user_ref.update({**mirror, "updatedAt": _base.firestore.SERVER_TIMESTAMP})


def _drop_user_to_demo_if_licensed(uid: str, license_id: str) -> None:
    """Drop a user to Demo only if they are still pointed at this exact
    license — activation is in-place (same uid/doc, no data migration), and
    downgrade must never delete or hide existing sessions/files, only stop
    new analysis creation once the account is back over the Demo cap."""
    user_ref = db().collection("users").document(uid)
    user_snap = user_ref.get()
    if user_snap.exists and (user_snap.to_dict() or {}).get("licenseId") == license_id:
        user_ref.update({
            **_mode_patch(MODE_DEMO),
            "updatedAt": _base.firestore.SERVER_TIMESTAMP,
            # The instant from which "has this account been back since?" is
            # asked. `_touch_user` throttles lastSeenAt to the hour, which
            # would leave reconciliation reading a landed revoke as unlanded
            # for that long; the checkpoint makes the account's next request
            # bypass the throttle exactly once. Written inside the update the
            # demotion already performs, so it costs no extra write here.
            "seenCheckpointAt": _base.firestore.SERVER_TIMESTAMP,
        })


# ---------------- institution seat administration ----------------
# Reached only via routers/institutions.py, gated on current_user + APPROVED +
# verified email present in the license's adminEmails — deliberately NOT
# verified_device and NOT Semper role=admin. Mint/whole-key-revoke stays on
# the existing device-attested admin path in routers/admin.py.

def is_institution_admin(license_doc: dict, email: str) -> bool:
    admin_emails = {e.strip().lower() for e in (license_doc.get("adminEmails") or [])}
    return bool(email) and email.strip().lower() in admin_emails


def list_licenses_administered_by(email: str) -> list[dict]:
    """Every live institution licence that names this address in `adminEmails`.

    The inverse of `is_institution_admin`, which can only answer for a licence
    id you already hold. Sign-in has an address and nothing else, so without
    this a member of institution IT has no way to reach their own roster
    except by being told the id out of band.

    `kind` and `status` are filtered in Python rather than added to the query:
    one address administers a handful of licences at most, and each extra
    equality clause on top of `array_contains` costs another composite index
    for no measurable gain.

    Same redaction as `institution_license_summary` — no key plaintext.
    """
    wanted = (email or "").strip().lower()
    if not wanted:
        return []
    out = []
    q = db().collection("licenses").where("adminEmails", "array_contains", wanted)
    for d in q.stream():
        data = d.to_dict() or {}
        if normalize_kind(data.get("kind")) != KIND_INSTITUTION:
            continue
        if (data.get("status") or "") == "revoked":
            continue
        out.append(_license_public(d.id, data))
    out.sort(key=lambda lic: lic["id"])
    return out


def find_user_by_email(email: str) -> dict | None:
    """The account holding this email, or None. Used to add a roster member.

    Institution IT works from an email address, but seats are keyed by uid —
    they have to be, since that is what every entitlement check has in hand.
    Demo is open to everyone, so requiring the person to have signed in once
    is not a barrier: it is the same step that gave them demo in the first
    place, and it means no invite records, no email-keyed documents and no
    second identity space to keep consistent.
    """
    wanted = (email or "").strip().lower()
    if not wanted:
        return None
    q = db().collection("users").where("email", "==", wanted).limit(1)
    for d in q.stream():
        return {**d.to_dict(), "uid": d.id}
    return None


#: Pending invites, keyed by a hash of the invited address. Top-level rather
#: than a subcollection of the licence so claiming one at sign-in is a single
#: document read: a subcollection would need a collection-group query (and its
#: index) on a path that runs for every account that does not yet hold a key.
_INVITES = "licenseInvites"


def _invite_ref(email: str):
    return db().collection(_INVITES).document(invite_id(email))


def _institution_member_patch(license_id: str, lic: dict) -> dict:
    """The user-document patch that puts someone on an institution licence.

    Shared by the two ways onto a roster — IT adding an existing account, and
    a newcomer redeeming an invite at sign-in — so the two cannot drift into
    entitling people differently.
    """
    patch = {
        **_mode_patch(MODE_LICENSED),
        "licenseId": license_id,
        "licenseKind": KIND_INSTITUTION,
        "licensePrefix": lic.get("keyPrefix") or "",
        "updatedAt": _base.firestore.SERVER_TIMESTAMP,
    }
    patch.update(_license_mirror_patch(lic))
    return patch


def _invite_public(doc_id: str, inv: dict) -> dict:
    return {
        "id": doc_id,
        "email": inv.get("email") or "",
        "licenseId": inv.get("licenseId") or "",
        "invitedByUid": inv.get("invitedByUid") or "",
        "createdAt": inv.get("createdAt"),
    }


def invite_institution_member(
    license_id: str, email: str, invited_by_uid: str,
) -> tuple[str, dict | None]:
    """Reserve a roster place for someone who has no account yet.

    Returns (error, invite). The invite is redeemed by `claim_pending_invite`
    the first time that address signs in, which is also the moment the person
    would otherwise have been given a Demo key.

    Deliberately consumes no seat and no slot. Until a real account claims it
    there is no uid, and every entitlement check in the system is keyed by uid
    — so counting an invite against `maxSeats` would mean decrementing a count
    for a person who may never arrive. An invite is a promise; the seat is
    taken when it is kept.
    """
    lic = get_license(license_id)
    if not lic:
        return "license_not_found", None
    if normalize_kind(lic.get("kind")) != KIND_INSTITUTION:
        return "license_not_found", None
    if (lic.get("status") or "active") == "revoked":
        return "license_revoked", None
    return _write_invite(license_id, email, invited_by_uid)


def _invite_is_stale(license_id: str) -> bool:
    """True when the licence an invite points at can never be claimed.

    `claim_pending_invite` deletes such an invite the moment the invited
    person signs in, so it holds no promise to anyone. Refusing a new invite
    on its behalf only makes the replacement licence undeliverable too.
    """
    if not license_id:
        return True
    lic = get_license(license_id)
    return not lic or (lic.get("status") or "active") == "revoked"


def _delete_license_invites(license_id: str, email_lock: str = "") -> None:
    """Drop the promises a revoked licence can no longer keep.

    Revoking used to leave `licenseInvites` untouched, which turned the most
    ordinary correction there is — mint against the wrong address, revoke,
    mint again for the right one — into a licence nobody could receive:
    `_write_invite` refused an address already promised elsewhere, so the
    second mint was minted and undeliverable.

    An individual licence has at most one invite, at the key derived from its
    `emailLock`, so it costs one delete guarded on `licenseId` — the same
    guard `revoke_institution_invite` uses, and for the same reason. Without
    an address to hash, and for an institution licence, it is the query
    instead, over a list that is small by construction.
    """
    if email_lock:
        ref = _invite_ref(email_lock)
        snap = ref.get()
        if snap.exists and ((snap.to_dict() or {}).get("licenseId") or "") == license_id:
            ref.delete()
        return
    for doc in db().collection(_INVITES).where("licenseId", "==", license_id).stream():
        doc.reference.delete()


def _write_invite(license_id: str, email: str,
                  invited_by_uid: str) -> tuple[str, dict | None]:
    """Record the promise itself, having already established the licence is
    one worth promising.

    Split from `invite_institution_member` because an individual licence is
    delivered the same way — mint against an address, let the person sign in —
    but reaches this point through a different set of checks. The record is
    identical either way, and `claim_pending_invite` reads the licence to
    decide what to grant, so the invite carries no notion of kind.
    """
    address = normalize_email(email)
    if not address:
        return "invalid_email", None

    ref = _invite_ref(address)
    existing = ref.get()
    if existing.exists:
        held = existing.to_dict() or {}
        # Re-inviting to the same licence is a no-op rather than an error, so
        # IT pasting a list twice is harmless. A different *live* licence is
        # refused: silently moving someone between institutions would be the
        # wrong default, and the address is the only identity we have to go
        # on. A dead one is overwritten — see `_invite_is_stale`.
        if (held.get("licenseId") or "") == license_id:
            return "", _invite_public(ref.id, held)
        if not _invite_is_stale(held.get("licenseId") or ""):
            return "invite_exists", None

    data = {
        "email": address,
        "licenseId": license_id,
        "invitedByUid": invited_by_uid,
        "createdAt": _base.firestore.SERVER_TIMESTAMP,
        "schemaVersion": SCHEMA_VERSION,
    }
    ref.set(data)
    return "", _invite_public(ref.id, ref.get().to_dict() or data)


def list_institution_invites(license_id: str) -> list[dict]:
    """Outstanding invites on one licence, oldest first.

    Sorted here rather than in Firestore: ordering by `createdAt` alongside the
    `licenseId` equality would need a composite index for a list that is small
    by construction (it drains as people sign in).
    """
    rows = [
        _invite_public(doc.id, doc.to_dict() or {})
        for doc in db().collection(_INVITES).where("licenseId", "==", license_id).stream()
    ]
    rows.sort(key=lambda r: (r.get("createdAt") is None, r.get("createdAt")))
    return rows


def revoke_institution_invite(license_id: str, invite_key: str) -> bool:
    """Withdraw an unclaimed invite. False when there is none to withdraw.

    Addressed by the invite's own id — the hash `list_institution_invites`
    returns — rather than by email. That keeps addresses out of request paths
    and access logs, and it is the id the console already has in hand.

    Guarded on `licenseId` so one institution's IT cannot delete another's
    invite by guessing an id.
    """
    ref = db().collection(_INVITES).document(invite_key)
    snap = ref.get()
    if not snap.exists:
        return False
    if ((snap.to_dict() or {}).get("licenseId") or "") != license_id:
        return False
    ref.delete()
    return True


def claim_pending_invite(user: dict) -> dict:
    """Redeem a pending invite for an account that has just become usable.

    See `_claim_pending_invite`, which also reports why a claim failed.
    """
    return _claim_pending_invite(user)[0]


def _claim_pending_invite(user: dict) -> tuple[dict, str]:
    """Redeem a pending invite; returns (user, error), error "" on no failure.

    Both licence kinds arrive here. An institution invite takes a seat; an
    individual one attaches the licence itself — the two ways a licence is
    delivered without anybody typing a key, and the only two. The invite
    record does not say which; the licence it points at does, which is why
    there is one collection rather than two.

    Returns the updated user when something was claimed, otherwise the user
    unchanged. Called from `ensure_entitlement`, i.e. exactly where a Demo key
    would otherwise be minted — so an invited newcomer lands licensed on their
    first request rather than demo-then-upgraded.

    The guards are `ensure_demo_license`'s, and they bound the cost: this runs
    only for an approved, verified account that holds no licence yet, which is
    a one-request window before the Demo key exists. It is not a per-request
    read.

    Email verification is required and not merely preferred. The invite names
    an address, and the address is the whole claim to the seat; honouring an
    unverified one would let anyone who can type someone else's address take
    the institution seat meant for them.
    """
    uid = user.get("uid")
    if not uid or user.get("licenseId"):
        return user, ""
    if user.get("access_status") != statuses.ACCESS_APPROVED or not user.get("emailVerified"):
        return user, ""
    address = normalize_email(user.get("email"))
    if not address:
        return user, ""

    ref = _invite_ref(address)
    snap = ref.get()
    if not snap.exists:
        return user, ""
    license_id = ((snap.to_dict() or {}).get("licenseId") or "")
    lic = get_license(license_id) if license_id else None
    if not lic:
        # The licence was deleted out from under the invite. Drop it rather
        # than leaving a record that can never be redeemed.
        ref.delete()
        return user, ""
    if (lic.get("status") or "active") == "revoked":
        ref.delete()
        return user, ""

    # No device lock is passed either way: the invite predates any device
    # choice, and the lock is bound on the first authed request that carries a
    # device id — see revalidate_device_lock.
    if normalize_kind(lic.get("kind")) == KIND_INSTITUTION:
        patch = _institution_member_patch(license_id, lic)
        err = claim_seat(license_id, uid, address, "", patch, invite_ref=ref)
    else:
        patch = _individual_member_patch(license_id, lic)
        err = claim_individual_license(license_id, uid, address, patch, invite_ref=ref)
    if err:
        # Leave the invite in place either way: seats exhausted or already
        # redeemed may be resolved by ops, and contention resolves itself on
        # the next request. Only the first two are worth a warning — logging
        # a lost race at the same level made a busy sign-in read exactly like
        # a licence with no room left.
        if err == _CONTENDED:
            log.info("invite claim lost the race uid=%s license=%s", uid, license_id)
        else:
            log.warning("invite claim failed uid=%s license=%s err=%s", uid, license_id, err)
        # Answer with the account as stored, not with the caller's copy. The
        # copy was read before this request began, and the request that beat
        # us to the claim has already granted the entitlement; returning the
        # stale dict serves one request as demo to someone who is licensed.
        # `ensure_demo_license` usually rescues this by re-reading, but it
        # returns early when there is no device id to mint against — which is
        # every browser request, since the consoles send no `X-Device-Id`.
        stored = db().collection("users").document(uid).get()
        if not stored.exists:
            return user, err
        return {**user, **(stored.to_dict() or {}), "uid": uid}, err
    return {**user, **patch, "uid": uid}, ""


def ensure_entitlement(user: dict, device_id: str | None) -> dict:
    """Give a newly-usable account whatever it is entitled to.

    An institution seat it was invited to, if there is one, else a Demo key.
    The order is the point: `ensure_demo_license` stamps a `licenseId`, and
    every later call short-circuits on that field, so minting Demo first would
    strand the invite permanently.

    That includes a claim that lost every transaction attempt to contention
    with nobody winning (TD-33): the invite is still pending, so this request
    is served without a licence — which resolves to Demo limits anyway — and
    the next request claims again. Any other failure (seats exhausted, already
    redeemed) is not transient, so Demo is minted as before.
    """
    claimed, err = _claim_pending_invite(user)
    if claimed.get("licenseId"):
        return claimed
    if err == _CONTENDED:
        return claimed
    return ensure_demo_license(claimed, device_id)


def add_institution_member(license_id: str, email: str,
                           invited_by_uid: str = "") -> tuple[str, dict | None, dict | None]:
    """Put someone on an institution license's roster. Returns (error, seat, invite).

    On an assigned license this entitles them immediately. On a floating one
    it makes them eligible; they still check out a lease to work, and adding
    a member therefore consumes no slot.

    Exactly one of `seat` and `invite` is set on success. An address with no
    account yet becomes a pending invite rather than a `user_not_found` error:
    IT works from a list of addresses and cannot make people sign up first, so
    refusing them was pushing a scheduling problem onto the wrong person.

    Idempotent for an active seat, and re-adding someone previously revoked
    gives them a fresh slot. A *disabled* seat is refused with
    `license_seat_disabled` — disable is a deliberate hold that IT lifts with
    `enabled=true`, and silently undoing it here would make the two routes
    fight over the same state.
    """
    lic = get_license(license_id)
    if not lic:
        return "license_not_found", None, None
    user = find_user_by_email(email)
    if not user:
        err, invite = invite_institution_member(license_id, email, invited_by_uid)
        return err, None, invite
    uid = user["uid"]

    # No device lock: IT adds a member before that member has picked a device,
    # and the lock is set the first time they actually use the license.
    err = claim_seat(license_id, uid, user.get("email") or email, "",
                     _institution_member_patch(license_id, lic))
    if err:
        return _public_claim_error(err, "license_seats_exhausted"), None, None
    seats = list_institution_seats(license_id)
    return "", next((s for s in seats if s["uid"] == uid), None), None


def institution_license_summary(license_id: str) -> dict | None:
    """Public (no key plaintext) summary of one institution license, for IT
    self-service — same redaction as the Semper-staff admin listing, scoped to
    callers who already passed the adminEmails membership check."""
    lic = get_license(license_id)
    return _license_public(license_id, lic) if lic else None


def list_institution_seats(license_id: str) -> list[dict]:
    out = []
    for doc in db().collection("licenses").document(license_id).collection("seats").stream():
        s = doc.to_dict() or {}
        out.append({
            "uid": doc.id,
            "email": s.get("email") or "",
            "deviceIdLock": s.get("deviceIdLock") or "",
            "status": s.get("status") or "active",
            # The lease is the point of the floating roster view: without it
            # IT cannot see who is actually using a seat right now, only who
            # is allowed to. Null on an assigned licence, which has no leases.
            "leaseExpiresAt": s.get("leaseExpiresAt"),
            "lastHeartbeatAt": s.get("lastHeartbeatAt"),
            "createdAt": s.get("createdAt"),
            "updatedAt": s.get("updatedAt"),
        })
    return out
