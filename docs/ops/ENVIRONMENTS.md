# GitHub Environments, secrets, and vars

Maintainer map of which GitHub Environment (or repo-level secret/var) each
workflow expects. Companion to [CI.md](CI.md) and [RELEASING.md](RELEASING.md).

## Free private org constraint

This repo lives on a **Free** private GitHub org plan. Environment *shells*
exist and workflows can still select them (`environment: staging`), but:

- **Required reviewers / deployment protection** on Environments may be limited
  or unavailable.
- Prefer storing **secrets and variables at the repository level** when
  Environment-scoped secrets are empty or cannot be set. Workflows read
  `secrets.*` / `vars.*` the same way either way.
- Classic branch protection sometimes returns 403; use a **ruleset** on `main`
  when the plan allows, otherwise treat green `CI OK` + PR-only merges as
  process.

Do not invent Environment-only secrets that the plan cannot enforce — document
the real placement.

## Environments in this repo

| Environment | Used by | Purpose |
|-------------|---------|---------|
| `staging` | [`deploy-backend.yml`](../../.github/workflows/deploy-backend.yml) | Cloud Run staging deploy + candidate smoke |
| `production` | same | Cloud Run production deploy + candidate smoke |
| `release` | [`release.yml`](../../.github/workflows/release.yml) | Signed APK + GitHub Release publish |
| `production-backup` | [`firestore-backup.yml`](../../.github/workflows/firestore-backup.yml) | Daily Firestore export |
| `restore-drill` | [`firestore-restore-drill.yml`](../../.github/workflows/firestore-restore-drill.yml) | Monthly / on-demand restore verify |

**Create `restore-drill` if it is missing.** Backup and restore credentials must
not share an environment: the drill identity can write to a throwaway project
and must never be able to import over production. See
[FIRESTORE_DATA_PROTECTION.md](../backend/FIRESTORE_DATA_PROTECTION.md).

## Secrets and variables by workflow

### CI ([`ci.yml`](../../.github/workflows/ci.yml))

| Name | Kind | Notes |
|------|------|-------|
| `GITLEAKS_LICENSE` | secret | Required for human PRs / pushes (gitleaks-action). Optional Dependabot copy under Dependabot secrets |
| `KEYSTORE_BASE64`, `KEY_ALIAS`, `KEY_PASSWORD`, `STORE_PASSWORD` | secrets | Optional for Tier 5 signature verify; unsigned notice if absent |

### Release ([`release.yml`](../../.github/workflows/release.yml))

| Name | Kind | Notes |
|------|------|-------|
| `KEYSTORE_BASE64`, `KEY_ALIAS`, `KEY_PASSWORD`, `STORE_PASSWORD` | secrets | Required to ship a signed APK |
| `INDIC_API_BASE_URL` | var | HTTPS API Gateway (preferred) or Cloud Run URL; required with `-PrequireCloudApi=true` |

Dispatch **from `main` only** — jobs no-op on other refs.

### Backend deploy ([`deploy-backend.yml`](../../.github/workflows/deploy-backend.yml))

| Name | Kind | Notes |
|------|------|-------|
| `GCP_WIF_PROVIDER`, `GCP_DEPLOY_SA` | secrets | Workload Identity Federation |
| `FIREBASE_PROJECT_ID`, `SHARED_DRIVE_ID`, `SERVICE_ACCOUNT_EMAIL` | vars | Runtime identity |
| `AUTO_APPROVE_HD`, `ADMIN_EMAILS`, `SUPPORT_EMAIL`, `NOTIFY_FROM` | vars | Access / mail |
| `TASKS_QUEUE`, `TASKS_LOCATION`, `TASKS_TARGET_BASE_URL`, `TASKS_INVOKER_SA` | vars | Async provisioning; leave empty for inline |
| **`REQUIRE_ATTESTED_UPLOADS`** | var | Production **must** be `1`. Empty string on deploy clears the Cloud Run flag |
| `DEMO_MAX_ANALYSES`, `LICENSED_MAX_SESSIONS_PER_USER` | vars | Cloud caps by `mode`. Unset → `25` / `999` (expression defaults in the workflow). Set `DEMO_MAX_ANALYSES` from the pre-deploy Firestore survey — every pre-licensing account is demo |
| `ADMIN_WEB_MFA_ENABLED`, `APP_CHECK_MODE`, `SELF_DEVICE_CHANGE_COOLDOWN_DAYS` | vars | Unset → `1` / `off` / `30`. `APP_CHECK_MODE` must stay `off` while a build without App Check is installed; `enforce` 403s it |

### Firestore backup / restore drill

See [FIRESTORE_DATA_PROTECTION.md](../backend/FIRESTORE_DATA_PROTECTION.md) for
WIF + bucket + drill project variables (`GCP_WORKLOAD_IDENTITY_PROVIDER`,
`FIRESTORE_BACKUP_BUCKET`, `FIRESTORE_RESTORE_DRILL_*`, etc.).

**`production-backup` must define these Environment (or repo) variables** or the
daily export fails at auth with an empty `workload_identity_provider`:

| Variable | Purpose |
|----------|---------|
| `GCP_WORKLOAD_IDENTITY_PROVIDER` | WIF provider resource name |
| `FIRESTORE_BACKUP_SERVICE_ACCOUNT` | Backup export SA email |
| `GCP_PROJECT` | Production GCP project id |
| `FIRESTORE_BACKUP_BUCKET` | Destination bucket name **without** `gs://` (`firestore-export.sh` adds the prefix) |

### Deploy Backend pitfall

The `project` workflow input is the **GCP project id**
(e.g. `indicvision-dic-app`), not the Cloud Run service name (`indic-api` /
`indic-api-staging`). A swapped value used to look like a “first deploy” and
then fail on smoke URL lookup with `PERMISSION_DENIED` on project `indic-api`.

## Default branch and `main` hygiene

- **Default branch:** `main` (integration + Dependabot target). Feature work
  lands via PR into `main`; do not use `damodar` as an integration branch.
  If GitHub still shows `damodar` as default, a repo admin must switch
  **Settings → General → Default branch → `main`** (the Actions token cannot
  change it). Until that flip, Dependabot may still open against `damodar`
  even though `dependabot.yml` sets `target-branch: main` after merge.
- **PR-only onto `main`:** no direct pushes when a ruleset can enforce it;
  emergency hotfix must be followed by a recording PR.
- **No force-push / no branch deletion** on `main`.
- **Required check:** `CI OK`.
- **`main` stays releasable:** if full CI on push fails, fix or `git revert`
  the same day — do not pile features on a red tip.
- **Label-driven heavy CI on PRs:** `e2e` / `release` / `full-ci`.
- **Batch releases** from `main` via `release.yml` when a coherent set is ready.
- **Docs change with behavior:** auth, quotas, deploy env vars, and CI modes
  update the matching doc in the same PR.

### Ruleset checklist (when the org plan allows)

- Default branch = `main`
- Require pull request before merging
- Require status checks: `CI OK`
- Require conversation resolution (if available)
- Block force-push and branch deletion on `main`
- Optional: required review count ≥ 1, dismiss stale approvals on new commits
