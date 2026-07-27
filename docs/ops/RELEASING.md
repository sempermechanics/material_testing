# Releasing inDIC

How a build goes from `main` to testers. Written for maintainers; nothing
here is needed for day-to-day contributions.

## Versioning

- `versionName` / `versionCode` live in [`app/build.gradle.kts`](../../app/build.gradle.kts).
- Beta builds are tagged `v<versionName>-beta.<n>` (e.g. `v1.0-beta.1`);
  stable releases are tagged `v<versionName>`.
- `versionCode` increases by 1 for every build handed to anyone.

## Release checklist

1. **Green working tree** — all changes committed, branch merged to the
   release branch through a reviewed PR.
2. **Green CI** — `ci-ok` must be green on the commit you intend to release.
3. **Bump version** in [`app/build.gradle.kts`](../../app/build.gradle.kts)
   (both `versionName` and `versionCode`), commit as `release: v<version>`.
4. **Tag and push**:

   ```bash
   git tag v1.0-beta.1
   git push origin main --tags
   ```

5. **Run the Release workflow** — go to Actions → Release → Run workflow.
   Provide the version tag, changelog, and channel (beta/stable). The
   workflow requires `release` environment approval before publishing.
6. **Smoke the release build on a device** — clean install, sign in,
   run one analysis, confirm the session syncs and each share target works.
7. **Distribute** to the approved-tester group and announce in Discussions.

## CI-based release (workflow_dispatch)

The [`release.yml`](../../.github/workflows/release.yml) workflow:

1. Builds a **signed release APK** using repository secrets (keystore, alias,
   passwords) stored in the `release` environment.
2. Verifies the arm64-v8a `.so` is packaged.
3. Creates a **GitHub Release** with the APK attached.

### Required secrets (in the `release` environment)

| Secret | Description |
|--------|-------------|
| `KEYSTORE_BASE64` | Base64-encoded release keystore |
| `KEY_ALIAS` | Signing key alias |
| `KEY_PASSWORD` | Key password |
| `STORE_PASSWORD` | Keystore password |

### Local gate before triggering release

```bash
./gradlew ciReleaseGate
```

This mirrors CI tiers 1–5 locally (quality gates + unit tests + R8 + release
assemble). Run emulator smoke separately:

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
