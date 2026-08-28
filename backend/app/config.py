import os


def _env_int(name: str, default: str) -> int:
    """Parse an integer env var, failing with a clear message rather than a bare
    ValueError traceback when the value is non-numeric."""
    raw = os.environ.get(name, default)
    try:
        return int(raw)
    except ValueError as e:
        raise RuntimeError(f"Environment variable {name}={raw!r} is not an integer") from e


class Settings:
    # There is deliberately no sign-in domain gate. Any account Firebase Auth
    # accepts may authenticate; whether it may *use* anything is decided by
    # access_status (see AUTO_APPROVE_HD / ADMIN_EMAILS below and
    # get_or_create_user). Earlier revisions carried WEB_CLIENT_ID and
    # ALLOWED_HD settings that no code read — do not reintroduce them.

    # Accounts whose verified email is in this domain are created APPROVED
    # automatically (e.g. "indicvision.com"). Everyone else is created PENDING
    # and must be approved individually. Empty = nobody auto-approved by domain.
    AUTO_APPROVE_HD = os.environ.get("AUTO_APPROVE_HD", "").lower()

    # Fleet-wide defaults for product limits. Per-user overrides live on the
    # Firestore users/{uid} document (maxSessions / maxFilesPerSession /
    # maxFrames); resolve_user_config merges override → these defaults.
    # MAX_SESSIONS_PER_USER = how many analyses a user may keep in the cloud;
    # MAX_FILES_PER_SESSION bounds one analysis (150 frames x raw+dat+csv +
    # reference + report + metadata ≈ 460, so 600 gives headroom);
    # MAX_FRAMES_PER_ANALYSIS is the deformed-frame ceiling the app enforces.
    MAX_SESSIONS_PER_USER = _env_int("MAX_SESSIONS_PER_USER", "4")
    MAX_FILES_PER_SESSION = _env_int("MAX_FILES_PER_SESSION", "600")
    MAX_FRAMES_PER_ANALYSIS = _env_int("MAX_FRAMES_PER_ANALYSIS", "150")

    # Version gate for the .dat archive codec (Phase 1.3 of the perf plan):
    # SessionZip's *read* side has understood a DatCodec-encoded .dat entry
    # since it shipped (self-describing by magic header, decode is a no-op
    # passthrough for a raw legacy entry) — but the *write* side must not
    # start producing encoded entries until every client that could ever
    # restore one has that read support. An already-installed app with zero
    # DatCodec awareness writes a restored .dat straight to disk with no
    # decode step; an encoded entry would silently corrupt that restore.
    # Default off (fleet-wide) until adoption of the DatCodec-aware app
    # version is confirmed high enough — flip via this env var, or canary a
    # single account first via the per-user override (PATCH /v1/admin/users/
    # {uid}/config, same mechanism as the numeric limits above).
    DAT_CODEC_ENCODING_ENABLED = os.environ.get(
        "DAT_CODEC_ENCODING_ENABLED", "false",
    ).strip().lower() == "true"

    # Comma-separated emails that are treated as admins (role=admin, always
    # approved) — they can call the /v1/admin/* endpoints. e.g.
    # "support@sempermechanics.com,damodar@sempermechanics.com".
    ADMIN_EMAILS = {
        e.strip().lower()
        for e in os.environ.get("ADMIN_EMAILS", "").split(",")
        if e.strip()
    }

    # Where "a new user is waiting for approval" mail goes. Same address the app
    # shows in Settings -> Help & support and on the pending-approval screen.
    SUPPORT_EMAIL = os.environ.get("SUPPORT_EMAIL", "support@sempermechanics.com")

    # Verified sender for outbound mail, e.g. "Semper <noreply@sempermechanics.com>",
    # and the Resend API key (the one secret this service holds — set it with
    # --set-secrets, never --set-env-vars). Either one empty disables
    # notification mail entirely: nothing is sent and nothing fails.
    NOTIFY_FROM = os.environ.get("NOTIFY_FROM", "")
    RESEND_API_KEY = os.environ.get("RESEND_API_KEY", "")

    # The runtime service account email Cloud Run runs as. Used for the keyless
    # self-impersonation that mints Drive-scoped tokens.
    SERVICE_ACCOUNT_EMAIL = os.environ.get("SERVICE_ACCOUNT_EMAIL", "")

    # Google Shared Drive id (the 5 TB company pool) and, optionally, a folder
    # inside it to root everything under. Defaults to the drive root.
    SHARED_DRIVE_ID = os.environ.get("SHARED_DRIVE_ID", "")
    ROOT_FOLDER_ID = os.environ.get("ROOT_FOLDER_ID", "") or SHARED_DRIVE_ID

    GCP_PROJECT = os.environ.get("GOOGLE_CLOUD_PROJECT", "")

    # The Firebase project whose ID tokens we accept (their audience). Usually
    # the same as GCP_PROJECT, but can differ if Firebase Auth lives in a
    # separate project — e.g. when org policy blocks adding Firebase to the main
    # one. Verification only needs the project id, not that project's resources.
    FIREBASE_PROJECT_ID = os.environ.get("FIREBASE_PROJECT_ID", "") or GCP_PROJECT

    # --- async session provisioning (Cloud Tasks) ---
    # Opening one Drive resumable session per file, inline, meant a 600-file
    # analysis made ~1200 sequential round-trips inside a 60s request budget.
    # Provisioning now runs as a Cloud Task; POST /v1/sessions returns
    # immediately with status PROVISIONING and the client polls the existing
    # /uploads resume endpoint.
    #
    # Leave TASKS_QUEUE empty to provision inline (local dev, tests, and any
    # deployment that has not created the queue) — the request is slower but the
    # behaviour is identical from the client's point of view.
    TASKS_QUEUE = os.environ.get("TASKS_QUEUE", "")
    TASKS_LOCATION = os.environ.get("TASKS_LOCATION", "asia-south1")
    # Where Cloud Tasks delivers the job. The service's own base URL.
    TASKS_TARGET_BASE_URL = os.environ.get("TASKS_TARGET_BASE_URL", "")
    # The service account Cloud Tasks signs the OIDC token with, and therefore
    # the only identity /v1/tasks/* accepts.
    TASKS_INVOKER_SA = os.environ.get("TASKS_INVOKER_SA", "") or SERVICE_ACCOUNT_EMAIL
    # Bounded fan-out when the worker opens resumable sessions.
    TASKS_PROVISION_WORKERS = _env_int("TASKS_PROVISION_WORKERS", "8")

    @property
    def tasks_enabled(self) -> bool:
        return bool(self.TASKS_QUEUE and self.TASKS_TARGET_BASE_URL and self.GCP_PROJECT)

    # --- pilot / testing switches (turn OFF for production) ---
    # 1 = skip ID-token + device-signature checks entirely. Lets you smoke-test
    # the Drive + Firestore path with curl before wiring the app. NEVER in prod:
    # it hands every caller the dev identity, which carries role=admin.
    DEV_INSECURE_AUTH = os.environ.get("DEV_INSECURE_AUTH", "") == "1"

    # Cloud Run always sets K_SERVICE, so this tells "deployed" from "on my
    # laptop". Bypassing auth on a deployed service additionally requires the
    # acknowledgement below, so a stray DEV_INSECURE_AUTH=1 cannot quietly ship
    # a wide-open backend — startup fails loudly instead (see main._startup).
    ON_CLOUD_RUN = bool(os.environ.get("K_SERVICE"))
    INSECURE_AUTH_ACK = os.environ.get("INSECURE_AUTH_I_ACCEPT_THE_RISK", "") == "1"
    # 1 = new users are created APPROVED instead of PENDING (smooth pilot).
    AUTO_APPROVE = os.environ.get("AUTO_APPROVE", "") == "1"

    # Temporary: /uploads returns Drive upload capability URLs and should require
    # device attestation. Testers hold builds that call it with an ID token only,
    # so accept both until the fleet has moved, then set this to 1. Leaving it off
    # never weakens a client that *does* attest — see deps.device_or_legacy_reader.
    REQUIRE_ATTESTED_UPLOADS = os.environ.get("REQUIRE_ATTESTED_UPLOADS", "") == "1"

    # Env vars the service cannot function without: GCP_PROJECT is the token
    # audience for ID-token verification, SERVICE_ACCOUNT_EMAIL mints Drive
    # tokens, SHARED_DRIVE_ID is where every file lives. A deployed service
    # missing any of these serves broken Drive/token calls, so startup rejects
    # it (see main._startup) instead of only warning.
    def missing_required(self) -> list[str]:
        required = ("GCP_PROJECT", "SERVICE_ACCOUNT_EMAIL", "SHARED_DRIVE_ID")
        return [k for k in required if not getattr(self, k)]


settings = Settings()
