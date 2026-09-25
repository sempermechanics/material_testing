from datetime import datetime, timezone
from typing import Annotated, List, Literal, Optional

from cryptography.hazmat.primitives.asymmetric import ec
from cryptography.hazmat.primitives.serialization import load_pem_public_key
from pydantic import BaseModel, Field, StringConstraints, field_validator, model_validator

from .config import settings
from .validation import DeviceId, DocumentId, SessionId

# "bundle" = Session.zip, holding only what a restore needs to rebuild a working
# session: raw/ (reference + deformed originals) and dat/ (engine results).
# "extras" = Extras.zip, holding the derived deliverables — csv/, reports/,
# processed/. Nothing in the app reads those back after a restore (they are
# regenerated on export), so keeping them out of the bundle is what lets a restore
# download only the bytes it actually needs.
#
# Both live at the session root, so a session still costs ~3 Firestore file docs
# rather than 3F+4. Older clients look for exactly one "bundle" and therefore keep
# restoring correctly against a split backup — they simply never see the extras.
Role = Literal["raw", "processed", "reports", "metadata", "csv", "dat", "bundle", "extras"]

_HEX = frozenset("0123456789abcdefABCDEF")
DisplayString = Annotated[str, StringConstraints(max_length=128)]


def _analysis_cap(value: Optional[int]) -> Optional[int]:
    """A licence's per-user analysis cap may not undercut demo.

    `resolve_user_config` floors the ceiling at `DEMO_MAX_ANALYSES` anyway;
    refusing here tells the operator at mint rather than storing a number that
    never takes effect.
    """
    if value is not None and value < settings.DEMO_MAX_ANALYSES:
        raise ValueError(
            f"maxAnalyses must be at least {settings.DEMO_MAX_ANALYSES} (the demo "
            "allowance); leave it out for the licensed default"
        )
    return value


class DeviceReg(BaseModel):
    deviceId: DeviceId
    publicKeyPem: str = Field(max_length=4096)
    model: DisplayString = ""
    osVersion: DisplayString = ""
    appVersion: DisplayString = ""

    @field_validator("model", "osVersion", "appVersion")
    @classmethod
    def _display_text(cls, value: str) -> str:
        # Device names and versions are human-facing and may legitimately be
        # Unicode. Reject only control characters that can corrupt logs/docs.
        if any(ord(char) < 0x20 or ord(char) == 0x7F for char in value):
            raise ValueError("must not contain control characters")
        return value

    @field_validator("publicKeyPem")
    @classmethod
    def _ec_public_pem(cls, v: str) -> str:
        try:
            key = load_pem_public_key(v.encode())
        except Exception as e:
            raise ValueError("publicKeyPem must be a valid PEM public key") from e
        if not isinstance(key, ec.EllipticCurvePublicKey):
            raise ValueError("publicKeyPem must be an EC public key")
        return v


class FileSpec(BaseModel):
    name: str = Field(min_length=1, max_length=256)
    role: Role
    bytes: int = Field(gt=0, le=5 * 1024 * 1024 * 1024)  # cap 5 GB
    sha256: str = Field(min_length=64, max_length=64)

    @field_validator("name")
    @classmethod
    def _no_path_separators(cls, v: str) -> str:
        # name is interpolated into a Firestore document id ("{sid}_{role}_{name}").
        # A "/" would add path segments (an even-segment path Firestore rejects with
        # a 500), and "." / ".." are reserved id values. Reject those and control
        # chars here so a bad name is a clean 422 at the edge, not a 500 mid-write.
        if "/" in v or "\\" in v:
            raise ValueError("name must not contain path separators")
        if v in (".", ".."):
            raise ValueError("name must not be '.' or '..'")
        if any(ord(c) < 0x20 for c in v):
            raise ValueError("name must not contain control characters")
        return v

    @field_validator("sha256")
    @classmethod
    def _sha256_hex(cls, v: str) -> str:
        if len(v) != 64 or any(c not in _HEX for c in v):
            raise ValueError("sha256 must be 64 hex characters")
        return v


class SessionCreate(BaseModel):
    specimen: str = Field(min_length=1, max_length=200)
    files: List[FileSpec] = Field(min_length=1, max_length=5000)
    metrics: dict = {}
    # The app's local analysis id. Lets the client reconcile its local sync
    # state against what actually exists in the cloud (and restore later).
    localSessionId: str = Field(default="", max_length=128)

    @field_validator("metrics")
    @classmethod
    def _bounded_metrics(cls, v: dict) -> dict:
        # metrics is written verbatim to the Firestore session doc, so an
        # unbounded client dict could inflate it toward the 1 MiB doc limit.
        # It is meant to be a small map of scalar run stats (pointsConverged,
        # avgIcgnIters, execMs), so reject anything larger or nested.
        if len(v) > 32:
            raise ValueError("metrics has too many keys")
        for key, val in v.items():
            if not isinstance(key, str) or len(key) > 64:
                raise ValueError("metrics keys must be strings <= 64 chars")
            # bool is a subclass of int; strings are length-bounded; no nesting.
            if isinstance(val, str):
                if len(val) > 256:
                    raise ValueError("metrics string values must be <= 256 chars")
            elif not isinstance(val, (int, float, bool)):
                raise ValueError("metrics values must be scalar (num / bool / short str)")
        return v


class FileComplete(BaseModel):
    sessionId: SessionId
    driveFileId: DocumentId
    bytes: int = Field(gt=0)
    md5: Optional[str] = None

    @field_validator("md5")
    @classmethod
    def _md5_hex(cls, v: Optional[str]) -> Optional[str]:
        if v is None:
            return v
        if len(v) != 32 or any(c not in _HEX for c in v):
            raise ValueError("md5 must be 32 hex characters")
        return v


class ProvisionTask(BaseModel):
    """Body of the Cloud Tasks provisioning callback. Validated like any other
    input — the queue is trusted to be Google, not to be well-behaved."""
    sessionId: SessionId


class UserConfigPatch(BaseModel):
    """Admin overrides for per-user product limits. Omitted fields stay unchanged;
    send JSON null to clear an override and re-inherit the fleet default."""
    maxSessions: Optional[int] = Field(default=None, gt=0)
    maxFilesPerSession: Optional[int] = Field(default=None, gt=0)
    maxFrames: Optional[int] = Field(default=None, gt=0)
    datCodecEncodingEnabled: Optional[bool] = None
    #: `plan` is the pre-rename spelling; both are accepted and fold onto
    #: `mode` in firestore_repo.set_user_config. Send one, not both.
    mode: Optional[Literal["demo", "licensed"]] = None
    plan: Optional[Literal["demo", "professional"]] = None


class LicenseActivate(BaseModel):
    """Typed license key from Settings. Canonicalised server-side before hash."""
    key: str = Field(min_length=8, max_length=64)


class AdminLicenseCreate(BaseModel):
    """Ops mint for a licensed key.

    `kind="individual"` (the default, and the only shape before institution
    licensing existed) requires both `emailLock` and `deviceIdLock` — one
    seat, redeemed by exactly one email on exactly one device.

    `kind="institution"` mints an institution key instead: no email/device
    lock at mint time. Membership is decided per-activation by `domainLock` (a
    verified-email domain match — see firestore_repo.activate_license), and
    `adminEmails` names the institution IT contacts who may manage seats via
    `backend/app/routers/institutions.py`
    (list/clear-device/enable-disable/revoke). `maxSeats` is an optional hard
    cap; omitted means unlimited.

    `"campus"` is the pre-rename spelling and is still accepted on the wire;
    it is normalised to `"institution"` before validation.
    """
    kind: Literal["individual", "institution"] = "individual"
    #: Orthogonal to `kind`. `timed` requires a future `expiresAt`; `perpetual`
    #: must not carry one. Default is perpetual — the shape that cannot expire
    #: on someone by accident.
    duration: Literal["perpetual", "timed"] = "perpetual"
    emailLock: Optional[str] = Field(default=None, min_length=3, max_length=320)
    deviceIdLock: Optional[DeviceId] = None
    domainLock: Optional[str] = Field(default=None, min_length=1, max_length=253)
    adminEmails: List[str] = Field(default_factory=list, max_length=20)
    maxSeats: Optional[int] = Field(default=None, gt=0, le=100000)
    #: Institution licenses only. `assigned` (the default) entitles every
    #: member of the roster. `floating` entitles only the `maxSeats` members
    #: holding a live lease; the rest are demo until one frees up.
    seating: Literal["assigned", "floating"] = "assigned"
    expiresAt: Optional[datetime] = None
    #: Days after `expiresAt` that entitlement continues, unchanged, so a
    #: renewal in flight does not interrupt work. Omitted uses the fleet
    #: default; 0 is a hard cliff.
    graceDays: Optional[int] = Field(default=None, ge=0, le=365)
    #: Informational. Never gates anything — a perpetual license whose support
    #: has lapsed still grants full use.
    supportUntil: Optional[datetime] = None
    maxAnalyses: Optional[int] = Field(default=None, gt=0)
    note: DisplayString = ""

    @field_validator("kind", mode="before")
    @classmethod
    def _kind_alias(cls, value):
        """Accept the pre-rename `campus` spelling from older ops tooling."""
        return "institution" if value == "campus" else value

    @field_validator("expiresAt", "supportUntil")
    @classmethod
    def _aware_utc(cls, value: Optional[datetime]) -> Optional[datetime]:
        """Coerce a naive datetime to UTC at the boundary.

        A payload may legitimately omit an offset. Letting a naive value
        through means every later comparison has to coerce or raise TypeError,
        so it is settled once, here.
        """
        if value is None or value.tzinfo is not None:
            return value
        return value.replace(tzinfo=timezone.utc)

    @field_validator("maxAnalyses")
    @classmethod
    def _max_analyses(cls, value: Optional[int]) -> Optional[int]:
        return _analysis_cap(value)

    @field_validator("emailLock")
    @classmethod
    def _email_lock(cls, value: Optional[str]) -> Optional[str]:
        if value is None:
            return value
        email = value.strip().lower()
        if "@" not in email or email.startswith("@") or email.endswith("@"):
            raise ValueError("emailLock must be an email address")
        if any(ord(char) < 0x20 or ord(char) == 0x7F for char in email):
            raise ValueError("emailLock must not contain control characters")
        return email

    @field_validator("domainLock")
    @classmethod
    def _domain_lock(cls, value: Optional[str]) -> Optional[str]:
        if value is None:
            return value
        domain = value.strip().lower()
        if "@" in domain or domain.startswith(".") or domain.endswith(".") or "." not in domain:
            raise ValueError("domainLock must be a bare domain, e.g. university.edu")
        if any(ord(char) < 0x20 or ord(char) == 0x7F for char in domain):
            raise ValueError("domainLock must not contain control characters")
        return domain

    @field_validator("adminEmails")
    @classmethod
    def _admin_emails(cls, value: List[str]) -> List[str]:
        out = []
        for raw in value:
            email = (raw or "").strip().lower()
            if "@" not in email or email.startswith("@") or email.endswith("@"):
                raise ValueError("adminEmails entries must be email addresses")
            if any(ord(char) < 0x20 or ord(char) == 0x7F for char in email):
                raise ValueError("adminEmails must not contain control characters")
            out.append(email)
        return out

    @model_validator(mode="after")
    def _duration_requires_matching_expiry(self) -> "AdminLicenseCreate":
        """A timed license without an expiry would be perpetual by accident,
        and a perpetual one carrying an expiry says two contradictory things.
        Reject both rather than silently picking a winner."""
        if self.duration == "timed":
            if self.expiresAt is None:
                raise ValueError("timed licenses require expiresAt")
            if self.expiresAt <= datetime.now(timezone.utc):
                raise ValueError("expiresAt must be in the future")
        elif self.expiresAt is not None:
            raise ValueError('perpetual licenses must not set expiresAt (use duration="timed")')
        return self

    @model_validator(mode="after")
    def _kind_requires_matching_locks(self) -> "AdminLicenseCreate":
        if self.kind == "individual":
            # `emailLock` alone. A device lock is accepted but no longer
            # demanded, because demanding it meant the customer had to read a
            # device id off their phone and send it to us before we could mint
            # anything. Left empty, the licence binds to the first device that
            # signs in as `emailLock` — see firestore_repo._device_lock_state.
            if not self.emailLock:
                raise ValueError("individual licenses require emailLock")
        else:  # institution
            if not self.domainLock:
                raise ValueError("institution licenses require domainLock")
            if not self.adminEmails:
                raise ValueError("institution licenses require at least one adminEmails entry")
        if self.seating == "floating":
            if self.kind != "institution":
                raise ValueError('only institution licenses may be floating')
            if self.maxSeats is None:
                # A floating pool with no cap is an assigned license with extra
                # steps: nobody would ever be refused a lease.
                raise ValueError("floating licenses require maxSeats")
        return self


class InstitutionSeatPatch(BaseModel):
    """Institution IT self-service seat edit. Both fields optional; send only
    what changes. `clearDeviceLock=true` lets a seat holder re-bind to a new
    device (lost phone, factory reset) without a support ticket to Semper
    staff. `enabled=false` drops the seat to Demo without freeing the slot —
    see routers/institutions.py and firestore_repo.set_seat_enabled."""
    clearDeviceLock: Optional[bool] = None
    enabled: Optional[bool] = None


class InstitutionSeatAdd(BaseModel):
    """Institution IT adding a member to the roster, by email.

    The person must already have an account — everyone can sign up and use
    demo, so "sign in once, then I'll add you" is the flow rather than an
    invite system. Adding by email keeps IT working from the address they
    already have; the backend resolves it to a uid.

    On a floating license this grants eligibility, not a slot: the member
    still checks out a lease when they want to work.
    """
    email: str = Field(min_length=3, max_length=320)

    @field_validator("email")
    @classmethod
    def _email(cls, value: str) -> str:
        email = value.strip().lower()
        if "@" not in email or email.startswith("@") or email.endswith("@"):
            raise ValueError("email must be an email address")
        if any(ord(char) < 0x20 or ord(char) == 0x7F for char in email):
            raise ValueError("email must not contain control characters")
        return email


class AdminLicenseUpdate(BaseModel):
    """Ops edit of an already-minted license — renewal, mostly.

    Before this existed a timed license could only be replaced, which meant
    issuing a new key and re-activating every holder. Extending `expiresAt`
    here re-entitles everyone already on the license in place.

    Terms only. It cannot change `kind`, the domain or email locks, or the
    key — those decide *who* the license is for, and changing them under
    existing holders is a different operation with different consequences.
    Send only what changes; at least one field is required.

    `clearDeviceLock=true` is the exception that proves the rule: the device
    lock says *where* the licence may be used, not who for, and staff have to
    be able to move a customer to a new phone. It is not a term — nothing is
    mirrored onto the holder and nothing is revoked — so it travels alongside
    the terms rather than among them, and it only applies to an individual
    licence, whose lock lives on the licence document. An institution licence
    keeps its locks on the seats.
    """
    expiresAt: Optional[datetime] = None
    graceDays: Optional[int] = Field(default=None, ge=0, le=365)
    supportUntil: Optional[datetime] = None
    maxSeats: Optional[int] = Field(default=None, gt=0, le=100000)
    maxAnalyses: Optional[int] = Field(default=None, gt=0)
    #: Drop the licence's analysis cap so holders get the licensed default.
    #: A flag, not `maxAnalyses: null`, because a null here already means
    #: "unchanged" for every other term.
    clearMaxAnalyses: Optional[bool] = None
    note: Optional[DisplayString] = None
    clearDeviceLock: Optional[bool] = None

    @field_validator("expiresAt", "supportUntil")
    @classmethod
    def _aware_utc(cls, value: Optional[datetime]) -> Optional[datetime]:
        if value is None or value.tzinfo is not None:
            return value
        return value.replace(tzinfo=timezone.utc)

    @field_validator("maxAnalyses")
    @classmethod
    def _max_analyses(cls, value: Optional[int]) -> Optional[int]:
        return _analysis_cap(value)

    @model_validator(mode="after")
    def _at_least_one_field(self) -> "AdminLicenseUpdate":
        if all(
            getattr(self, name) is None
            for name in ("expiresAt", "graceDays", "supportUntil", "maxSeats",
                         "maxAnalyses", "clearMaxAnalyses", "note", "clearDeviceLock")
        ):
            raise ValueError("at least one field must be set")
        if self.clearMaxAnalyses and self.maxAnalyses is not None:
            raise ValueError("send maxAnalyses or clearMaxAnalyses, not both")
        return self
