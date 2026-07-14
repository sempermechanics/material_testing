import os


class Settings:
    # Audience of the Google ID token = the OAuth *Web* client ID the Android
    # app uses (BuildConfig.GOOGLE_WEB_CLIENT_ID). Must match exactly.
    WEB_CLIENT_ID = os.environ.get("WEB_CLIENT_ID", "")

    # Hard sign-in gate. If set (e.g. "company.com") ONLY that hosted domain may
    # sign in. Empty = any Google account may sign in (and lands PENDING unless
    # auto-approved below) — used for the "outside collaborators request access"
    # model.
    ALLOWED_HD = os.environ.get("ALLOWED_HD", "")

    # Accounts whose verified email is in this domain are created APPROVED
    # automatically (e.g. "indicvision.com"). Everyone else is created PENDING
    # and must be approved individually. Empty = nobody auto-approved by domain.
    AUTO_APPROVE_HD = os.environ.get("AUTO_APPROVE_HD", "")

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

    # --- pilot / testing switches (turn OFF for production) ---
    # 1 = skip ID-token + device-signature checks entirely. Lets you smoke-test
    # the Drive + Firestore path with curl before wiring the app. NEVER in prod.
    DEV_INSECURE_AUTH = os.environ.get("DEV_INSECURE_AUTH", "") == "1"
    # 1 = new users are created APPROVED instead of PENDING (smooth pilot).
    AUTO_APPROVE = os.environ.get("AUTO_APPROVE", "") == "1"


settings = Settings()
