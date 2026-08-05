# Releasing Semper

How a build goes from `main` to testers. Written for maintainers; nothing
here is needed for day-to-day contributions.

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

1. **Green working tree** — all changes committed, branch merged to the
   release branch through a reviewed PR.
2. **Green CI** — `ci-ok` must be green on the commit you intend to release.
3. **Tag and push** the commit you intend to release:

   ```bash
   git tag v1.0-beta.1
   git push origin main --tags
   ```

4. **Run the Release workflow** — go to Actions → Release → Run workflow.
   Provide the version tag, changelog, and channel (beta/stable); the version
   drives `versionName`/`versionCode` automatically (see Versioning). The
   workflow requires `release` environment approval before publishing.
6. **Smoke the release build on a device** — clean install, sign in,
   run one analysis, confirm the session syncs and each share target works.
7. **Distribute publicly (manual)** — this workflow only builds a signed APK and
   a **private** GitHub Release. Customer distribution is separate:
   - Upload the APK (GitHub Release on
     [semperdic/website](https://github.com/semperdic/website) preferred) + update
     `downloads/manifest.json`, push so GitHub Pages redeploys — see that repo’s
     `RELEASING.md` — and/or publish on Play Store.
   - Announce on https://semperdic.github.io/website/ / Discussions on
     `semperdic/website` (not this private repo).

## CI-based release (workflow_dispatch)

The [`release.yml`](../../.github/workflows/release.yml) workflow:

1. Builds a **signed release APK** using repository secrets (keystore, alias,
   passwords) stored in the `release` environment. The `signingConfigs.release`
   block in `app/build.gradle.kts` reads the `SIGNING_*` env vars the workflow
   sets; if the keystore is absent the variant stays **unsigned** rather than
   silently debug-signed.
2. Requires environment variable **`INDIC_API_BASE_URL`** (HTTPS API Gateway or
   Cloud Run URL) and builds with `-PrequireCloudApi=true`. Missing/empty URL
   fails the job — cloud sync must not ship silently disabled.
3. Verifies the arm64-v8a `.so` is packaged.
4. **Verifies the signature** with `apksigner verify` — the release fails here
   if the APK is not validly signed with the release key.
5. Creates a **GitHub Release** with the APK attached.

### Required secrets (in the `release` environment)

| Secret | Description |
|--------|-------------|
| `KEYSTORE_BASE64` | Base64-encoded release keystore |
| `KEY_ALIAS` | Signing key alias |
| `KEY_PASSWORD` | Key password |
| `STORE_PASSWORD` | Keystore password |

### Required variables (in the `release` environment)

| Variable | Description |
|----------|-------------|
| `INDIC_API_BASE_URL` | HTTPS base URL of the API Gateway (preferred) or Cloud Run service |

### Backend staging / production

Use [`deploy-backend.yml`](../../.github/workflows/deploy-backend.yml): choose
`staging` or `production`, supply GCP project/region. Post-deploy smoke hits
`/readyz`; failure auto-rolls traffic to the previous revision. See
[CI.md](CI.md) § Backend deploy.

### Local gate before triggering release

```bash
./gradlew ciReleaseGate
```

This mirrors CI **tiers 1 and 5** locally (quality gates + unit tests + R8 +
release assemble). It does **not** run the native (tier 2) or backend (tier 4)
suites — run those separately. Emulator smoke also runs separately:

```bash
./gradlew :app:connectedDebugAndroidTest -PabiFilters=x86_64
```

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
document the rewrite in the PR / release notes.
