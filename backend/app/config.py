import os
import re


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
    #
    # There is exactly one cap per mode, and `mode` picks between them:
    # DEMO_MAX_ANALYSES for an unlicensed account, LICENSED_MAX_SESSIONS_PER_USER
    # for a licensed one (unless a key or admin override sets a tighter
    # ceiling). Both are the number of analyses the account may keep in the
    # cloud — routers/sessions.py enforces creation against whichever applies.
    #
    # `MAX_SESSIONS_PER_USER` USED TO BE that number for everyone, at a default
    # of 4. It is deliberately gone rather than left unread: keeping a variable
    # that a deployment still sets, and that silently no longer does anything,
    # is worse than removing it and saying so. A service that still sets it now
    # gets DEMO_MAX_ANALYSES for unlicensed users — see
    # docs/backend/BACKEND_SETUP_GCP.md.
    #
    # MAX_FILES_PER_SESSION bounds one analysis (150 frames x raw+dat+csv +
    # reference + report + metadata ≈ 460, so 600 gives headroom);
    # MAX_FRAMES_PER_ANALYSIS is the deformed-frame ceiling the app enforces.
    DEMO_MAX_ANALYSES = _env_int("DEMO_MAX_ANALYSES", "25")
    # PRO_MAX_SESSIONS_PER_USER is the pre-rename name, still read as the
    # default so the rename did not need a coordinated env change.
    # deploy-backend.yml pins LICENSED_MAX_SESSIONS_PER_USER and warns while the
    # old name is still set on the service; delete this fallback once no
    # service carries it (TD-45).
    LICENSED_MAX_SESSIONS_PER_USER = _env_int(
        "LICENSED_MAX_SESSIONS_PER_USER",
        os.environ.get("PRO_MAX_SESSIONS_PER_USER", "999"),
    )

    # Grace window stamped onto a newly minted timed license when the mint
    # request does not name one. It exists so a renewal in flight does not
    # strand a paying user mid-project; entitlements stay FULL throughout.
    #
    # This is a mint-time default only. A license already in Firestore that
    # carries no graceDays reads as ZERO, not as this value — see
    # firestore_repo._grace_days. Otherwise deploying a grace default would
    # retroactively reinstate every account that expired within the window.
    LICENSE_GRACE_DAYS_DEFAULT = _env_int("LICENSE_GRACE_DAYS_DEFAULT", "14")

    # How long a floating-seat lease lasts before it stops entitling anyone,
    # and how often the app should renew it. A lease must outlive a working
    # session comfortably — losing a seat because of a tunnel or a lunch break
    # would be worse than a crashed client parking one — so the window is long
    # and the heartbeat is what actually keeps it alive.
    #
    # A crashed or uninstalled client therefore holds its slot for up to
    # LICENSE_LEASE_HOURS. That only costs anything on a pool that is full, and
    # the expiry sweep reclaims it without anyone intervening.
    LICENSE_LEASE_HOURS = _env_int("LICENSE_LEASE_HOURS", "8")
    LICENSE_LEASE_HEARTBEAT_MINUTES = _env_int("LICENSE_LEASE_HEARTBEAT_MINUTES", "30")
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

    # Emails treated as admins (role=admin, always approved) — they can call
    # the /v1/admin/* endpoints. e.g.
    # "support@indicvision.com damodar@indicvision.com".
    # Separated by whitespace, `;` or `,`. Prefer spaces, for the reason
    # CONSOLE_ORIGINS gives below: the deploy action's env_vars block splits
    # pairs on commas, so a comma-separated list of operators arrives
    # truncated to the first address — and the operator desk is exactly where
    # losing the second name is least visible until someone is locked out.
    ADMIN_EMAILS = {
        e.lower()
        for e in re.split(r"[\s,;]+", os.environ.get("ADMIN_EMAILS", ""))
        if e
    }

    # --- browser dashboards ---------------------------------------------------
    # Origins allowed to call the API from a browser. The dashboards live on
    # Firebase Hosting (app.sempermechanics.com, and the project's own
    # firebaseapp.com host), never on the gateway host, so every console fetch
    # is cross-origin and carries Authorization, which makes the browser
    # preflight it. Without a matching Access-Control-Allow-Origin the
    # response is discarded and the page silently does nothing. The phone
    # never sends an Origin and is unaffected. Exact origins (scheme + host),
    # no wildcard, separated by whitespace, `;` or `,`. Prefer spaces: the
    # deploy action's env_vars block splits pairs on commas, so a
    # comma-separated value arrives truncated with its tail as a stray key.
    CONSOLE_ORIGINS = [
        o.rstrip("/")
        for o in re.split(
            r"[\s,;]+",
            os.environ.get(
                "CONSOLE_ORIGINS",
                "https://app.sempermechanics.com "
                "https://indicvision-dic-app-auth.firebaseapp.com",
            ),
        )
        if o
    ]

    # --- staff console second factor ---------------------------------------
    # Every state-changing /v1/admin/* route requires a device attestation: an
    # ECDSA signature from a registered device keypair, which proves the call
    # came from a specific enrolled phone and not merely from a stolen ID
    # token. A browser cannot produce one, which is why the staff console was
    # read-only.
    #
    # These two settings are the deliberate substitute. A browser caller is
    # accepted for those routes when the ID token carries a *second factor*
    # (firebase.sign_in_second_factor, present only when MFA was actually
    # completed) and the sign-in behind it is recent. That is a real, checkable
    # control rather than an absent one — a leaked token from a session that
    # never did MFA is still refused — but it is weaker than device binding,
    # and the freshness window is what limits the damage a stolen token can do.
    #
    # ADMIN_WEB_MFA_ENABLED=0 disables the browser path entirely and restores
    # attestation-only admin. Set it that way if the console is not in use.
    ADMIN_WEB_MFA_ENABLED = os.environ.get("ADMIN_WEB_MFA_ENABLED", "1") == "1"
    # How old a sign-in may be and still authorise a state change, in seconds.
    # Short on purpose: this is "sudo mode", re-entered by re-authenticating,
    # not a session length. Dashboard reads that sit behind step-up use this
    # too (institution IT console, account unbind/bundle).
    ADMIN_WEB_REAUTH_SECONDS = _env_int("ADMIN_WEB_REAUTH_SECONDS", "900")
    # Tighter window for whole-licence revoke: password (or Google re-auth)
    # plus TOTP must be fresh, not merely "signed into the dashboard earlier".
    ADMIN_WEB_REVOKE_REAUTH_SECONDS = _env_int(
        "ADMIN_WEB_REVOKE_REAUTH_SECONDS", "120"
    )

    # Both settings above govern the browser step-up for the *user* and
    # *institution IT* tiers too, not only for staff: the same trade is being
    # made either way. The names are historical — the staff console is what
    # first needed them.

    # How long a holder must wait between changing their own device. A second
    # factor proves who is asking, not how often, so without this one person
    # could re-bind daily and pass a single licence round a lab. Counted
    # against `deviceChangedAt`, which only the self-service route writes:
    # staff- and IT-initiated changes neither read nor write it, so a support
    # request is never blocked by a cooldown the holder has spent. Set to 0 to
    # disable the wait entirely.
    SELF_DEVICE_CHANGE_COOLDOWN_DAYS = _env_int("SELF_DEVICE_CHANGE_COOLDOWN_DAYS", "30")

    # --- App Check (device callers only) ------------------------------------
    # The Firebase Web API key ships inside the APK (google-services.json) and
    # is an identifier, not a secret, so anyone can mint a genuine ID token from
    # a script. For the routes behind `verified_device` or a step-up tier that
    # buys an attacker nothing — a DeviceKeyManager signature is a stronger
    # proof than App Check. The exposed set is the ID-token-only routes, and
    # `POST /v1/licenses/checkout` is the one worth abusing: it reads the device
    # id from a *header* and doubles as the seat heartbeat, so a scripted client
    # can hold a licence's floating seats under invented device ids.
    #
    # App Check closes that by attesting the *app binary* (Play Integrity) as
    # well as the account. It is required only of callers that send
    # `X-Device-Id` — that is the app, and it is the header the abuse needs.
    # Browsers never send it, so the four consoles are unaffected and need no
    # reCAPTCHA provider.
    #
    #   off     — header ignored entirely (the default, and what to run until
    #             an App Check-carrying build is the fleet).
    #   monitor — verified when present, logged when absent or bad, never
    #             refused. The rollout setting: it tells you what fraction of
    #             live traffic would break before anything does.
    #   enforce — a device caller without a valid token is refused 403.
    #
    # Mirrors Firebase's own unenforced/enforced rollout, with `monitor` named
    # for what it is. Flipping to `enforce` before the fleet has updated locks
    # out every older build, so it is deliberately not the default.
    APP_CHECK_MODE = os.environ.get("APP_CHECK_MODE", "off").strip().lower()

    # Where "a new user is waiting for approval" mail goes. Same address the app
    # shows in Settings -> Help & support and on the pending-approval screen.
    SUPPORT_EMAIL = os.environ.get("SUPPORT_EMAIL", "support@sempermechanics.com")

    # Verified sender for outbound mail, e.g. "Semper <noreply@indicvision.com>",
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
    # Manifests this small are provisioned inside the request even when a queue
    # exists. A bundle upload is three files (~1 s of Drive calls once the
    # folders are cached); queueing it instead costs a task hop plus the
    # client's first 1 s poll, so it was slower, not faster. 0 = always queue.
    INLINE_PROVISION_MAX_FILES = _env_int("INLINE_PROVISION_MAX_FILES", "8")

    # A signed call may carry a client-minted nonce `t1.<unix seconds>.<random>`
    # instead of one from POST /v1/challenge, saving a round-trip per call. It
    # is accepted within this many seconds of server time either way; outside
    # it the client falls back to a challenge. 0 disables client nonces.
    CLIENT_NONCE_WINDOW_SECONDS = _env_int("CLIENT_NONCE_WINDOW_SECONDS", "120")

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
