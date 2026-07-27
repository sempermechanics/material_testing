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
2. **Local gates** (same as CI, faster feedback):

   ```bash
   ./gradlew spotlessCheck :app:detekt :app:lintDebug :app:testDebugUnitTest
   ./gradlew :app:minifyReleaseWithR8
   ```

3. **Native engine tests** (engine changes only — the engine is frozen
   during beta):

   ```bash
   cd app/src/test/cpp
   cmake -B build && cmake --build build && ./build/dic_tests
   ```

4. **Write the release notes** — `git log <last-tag>..HEAD --oneline` is the
   raw material; group it into what testers will notice.
5. **Bump version** in [`app/build.gradle.kts`](../../app/build.gradle.kts)
   (both `versionName` and `versionCode`), commit as `release: v<version>`.
6. **Tag and push**:

   ```bash
   git tag v1.0-beta.1
   git push origin main --tags
   ```

7. **CI must be fully green** on the tagged commit — all jobs: native
   engine tests, both sanitizer runs, Kotlin compile + gates, the all-ABI
   native build, and the emulator smoke test.
8. **Build the signed release** locally (signing config is not in CI):

   ```bash
   ./gradlew :app:assembleRelease
   ```

   The universal APK lands under `app/build/outputs/apk/release/`.
9. **Smoke the release build on a device** — clean install, sign in,
   run one analysis, confirm the session syncs and each share target works.
10. **Distribute** to the approved-tester group and announce in Discussions
    with the release notes from step 4.

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
