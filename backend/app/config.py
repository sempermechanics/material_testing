import os


class Settings:
    # There is deliberately no sign-in domain gate. Any account Firebase Auth
    # accepts may authenticate; whether it may *use* anything is decided by
    # access_status (see AUTO_APPROVE_HD / ADMIN_EMAILS below and
    # get_or_create_user). Earlier revisions carried WEB_CLIENT_ID and
    # ALLOWED_HD settings that no code read — do not reintroduce them.

    # Accounts whose verified email is in this domain are created APPROVED
    # automatically (e.g. "indicvision.com"). Everyone else is created PENDING
    # and must be approved individually. Empty = nobody auto-approved by domain.
    AUTO_APPROVE_HD = os.environ.get("AUTO_APPROVE_HD", "")

    # Per-user quotas. MAX_SESSIONS_PER_USER = how many analyses a user may keep
    # in the cloud; MAX_FILES_PER_SESSION bounds one analysis (150 frames x
    # raw+dat+csv + reference + report + metadata ≈ 460, so 600 gives headroom).
    MAX_SESSIONS_PER_USER = int(os.environ.get("MAX_SESSIONS_PER_USER", "4"))
    MAX_FILES_PER_SESSION = int(os.environ.get("MAX_FILES_PER_SESSION", "600"))

    # Comma-separated emails that are treated as admins (role=admin, always
    # approved) — they can call the /v1/admin/* endpoints. e.g.
    # "support@indicvision.com,damodar@indicvision.com".
    ADMIN_EMAILS = {
        e.strip().lower()
        for e in os.environ.get("ADMIN_EMAILS", "").split(",")
        if e.strip()
    }

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


settings = Settings()
