# Releasing Semper

How a build goes from **`main`** to testers. Written for maintainers; nothing
here is needed for day-to-day contributions. Secrets/vars layout:
[ENVIRONMENTS.md](ENVIRONMENTS.md).

## Alpha (private testers)

Private alpha uses the same **`beta`** Release channel — there is no separate
`alpha` input on [`release.yml`](../../.github/workflows/release.yml).

1. Tag and run Release on **`main`** with channel **`beta`**
   (e.g. `v1.0-beta.1`) → private GitHub Release + signed APK.
2. Sideload onto tester devices (Pixel OK). Testers need access to this private
   repo’s Releases (or you pass the APK out-of-band).
3. Smoke sign-in, one analysis, backup/sync, and open results. Do not publish to
   Play or the public website for this ring.

Archive the R8 mapping artifact before 90-day expiry (same as any beta).

## Versioning

- A CI release sets the version from the workflow inputs: `versionName` comes
  from the `version` input (leading `v` stripped) and `versionCode` from the
  workflow run number, passed to Gradle as `-PversionName` / `-PversionCode`.
  You do **not** hand-edit `app/build.gradle.kts` for a CI release.
- The values in [`app/build.gradle.kts`](../../app/build.gradle.kts) (`1` / `1.0`)
  are only the fallback for local builds that pass no `-P` overrides.
- Beta builds are tagged `v<versionName>-beta.<n>` (e.g. `v1.0-beta.1`);
  stable releases are tagged `v<versionName>`.
- `versionCode` increases for every build handed to anyone (the run number is
  monotonic, so successive releases always increase).

## Release checklist

1. **Green working tree** — all changes committed, branch merged to **`main`**
   through a reviewed PR.
2. **Green CI** — `CI OK` must be green on the `main` commit you intend to
   release (full matrix on push to `main`).
3. **Tag and push** the commit you intend to release:

   ```bash
   git tag v1.0-beta.1
   git push origin main --tags
   ```

4. **Run the Release workflow** — go to Actions → Release → Run workflow.
   Select branch **`main`**. Provide the version tag, changelog, and channel
   (beta/stable); the version drives `versionName`/`versionCode` automatically
   (see Versioning). Jobs refuse to run on other refs. The workflow uses the
   `release` environment (and repo-level secrets/vars on Free orgs).
5. **Smoke the release build on a device** — clean install, sign in,
   run one analysis, confirm the session syncs and each share target works.
6. **Distribute publicly (manual)** — this workflow only builds a signed APK and
   a **private** GitHub Release. Customer distribution is separate:
   - Upload the APK (GitHub Release on
     [semperdic/website](https://github.com/semperdic/website) preferred) + update
     `downloads/manifest.json`, push so GitHub Pages redeploys — see that repo’s
     `RELEASING.md` — and/or publish on Play Store.
   - Announce on https://semperdic.github.io/website/ / Discussions on
     `semperdic/website` (not this private repo).

## CI-based release (workflow_dispatch)

The [`release.yml`](../../.github/workflows/release.yml) workflow runs three
jobs: `verify` → `build-release` → `publish`. Dispatch from **`main` only**.

### `verify` — the gate

Green CI used to be a checklist item in this file, which meant a red suite could
still be signed and published. It is now a job the build depends on:

- `python scripts/render_legal_pages.py --check` — the published Privacy Policy
  and Terms still match `docs/legal/`.
- `./gradlew :app:testDebugUnitTest` — the Android unit suite.
- Backend `ruff check app/ tests/ scripts/ ../scripts/` and
  `pytest tests/ -q --cov=app --cov-fail-under=75`.

`build-release` declares `needs: verify`, so none of the signing steps run if any
of the above fails.

### `build-release` — sign and check

1. Builds a **signed release APK** using repository secrets (keystore, alias,
   passwords) — Environment-scoped when available, otherwise **repo-level**
   secrets on Free private orgs. The `signingConfigs.release` block in
   `app/build.gradle.kts` reads the `SIGNING_*` env vars the workflow sets; if
   the keystore is absent the variant stays **unsigned** rather than silently
   debug-signed.
2. Requires variable **`INDIC_API_BASE_URL`** (HTTPS API Gateway or Cloud Run
   URL) and builds with `-PrequireCloudApi=true`. A missing, empty or non-HTTPS
   URL fails the job — cloud sync must not ship silently disabled, and ID tokens
   must not go out in cleartext.
3. Verifies the arm64-v8a `.so` is packaged.
4. **Verifies the signature** with `apksigner verify` — the release fails here
   if the APK is not validly signed with the release key. Newer Android
   build-tools print `V2 Signer: certificate SHA-256 digest:` (and similar);
   older ones used `Signer #1 certificate SHA-256 digest:`. The workflow accepts
   both.
5. **Verifies `assetlinks.json` lists the release certificate.** It extracts the
   SHA-256 digest from the signed APK, reformats it to the colon-separated
   uppercase form Digital Asset Links uses, and greps
   `firebase-hosting/public/.well-known/assetlinks.json` for it. If the
   fingerprint is absent the release fails with instructions.

   This check exists because the failure it prevents is invisible. App Links only
   verify when the *release* signing certificate is in the hosted file; when it is
   not, the email sign-in and password-reset links stop opening the app and fall
   back to a browser disambiguation dialog — phishable, and indistinguishable from
   an app bug. Rotating the signing key or moving to Play App Signing means adding
   the new fingerprint here and redeploying Hosting.
6. Uploads two artifacts: the APK, and **`release-mapping-<version>`**, the R8
   mapping file (90-day retention, `if-no-files-found: error`).

**The R8 mapping is not attached to the GitHub Release, deliberately.** It is the
deobfuscation key — publishing it would undo the obfuscation for everyone — but
without it a field stack trace from that build is unreadable, and it cannot be
regenerated afterwards. Archive it somewhere durable before the 90-day artifact
retention expires. This is a step you have to take by hand.

### `publish`

Creates the **GitHub Release** with the APK attached, gated on the `release`
environment.

### Required secrets (repo or `release` environment)

| Secret | Description |
|--------|-------------|
| `KEYSTORE_BASE64` | Base64-encoded release keystore |
| `KEY_ALIAS` | Signing key alias |
| `KEY_PASSWORD` | Key password |
| `STORE_PASSWORD` | Keystore password |

### Required variables (repo or `release` environment)

| Variable | Description |
|----------|-------------|
| `INDIC_API_BASE_URL` | HTTPS base URL of the API Gateway (preferred) or Cloud Run service |

### Backend staging / production

Use [`deploy-backend.yml`](../../.github/workflows/deploy-backend.yml): choose
`staging` or `production`, supply GCP project/region. Updates deploy with
`no_traffic`, tag a candidate, smoke `/readyz` with an ID token, then promote.
First-time Cloud Run creates omit `no_traffic` (Cloud Run rejects it on create).
See [CI.md](CI.md) § Backend deploy and [ENVIRONMENTS.md](ENVIRONMENTS.md).

#### Bumping backend dependencies

The image installs from `backend/requirements.lock` with `--require-hashes`, so a
version bump is two files, in this order:

```bash
cd backend
# 1. Edit requirements.txt (the direct dependency you actually want to move).
# 2. Regenerate the hashed lock from it.
pip install pip-tools
pip-compile --generate-hashes --output-file requirements.lock requirements.txt
```

CI tier 4 then proves the lock resolves under `--require-hashes` on Python 3.12
*and* still covers every direct dependency, so a stale or hand-edited lock fails
in CI rather than in the Cloud Build step of a deploy. Never edit
`requirements.lock` by hand — the hashes will not match and the image will fail
to build.

### Local gate before triggering release

```bash
./gradlew ciReleaseGate
```

This mirrors CI **tiers 1 and 5** locally (quality gates + unit tests + R8 +
release assemble). It does **not** run the backend suite, the emulator tier, or
the two always-on gates. Run those separately:

```bash
python scripts/render_legal_pages.py --check                     # legal-pages
cd backend && pytest tests/ -q --cov=app --cov-fail-under=75     # tier 4
./gradlew :app:connectedDebugAndroidTest -PabiFilters=x86_64     # tier 3
```

Engine host and sanitizer suites are not part of this repo's gate — they run in
`semperdic/semper-dic-engine` against the commit this repo pins.

## After the release

- Verify a fresh install from the distributed artifact (not a dev build).
- Watch Firestore (sessions collection) and Cloud Logging for the first synced sessions.
- Open a milestone for the next version and triage incoming beta feedback
  against it.

## History rewrite / re-clone

If git history is rewritten (for example to purge the old prebuilt OpenCV SDK
under `app/src/main/cpp/opencv/` from every commit), collaborators must
**re-clone** or hard-reset to the rewritten tip. Force-pushed branches invalidate
existing local clones' merge bases. Coordinate with the team before rewriting;
document the rewrite in the PR / release notes. Do **not** force-push `main` for
routine fixes — prefer `git revert`.
