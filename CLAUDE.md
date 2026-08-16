# Semper — Claude Code

Read [CONTEXT.md](CONTEXT.md) before editing. Human build/test source of truth:
[CONTRIBUTING.md](CONTRIBUTING.md).

## Commands

Windows: `gradlew.bat`. Quote `-Pandroid.testInstrumentationRunnerArguments…` in PowerShell.

| Command | What |
|---------|------|
| `./gradlew ciReleaseGate` | Spotless, detekt, lintDebug, unit tests, R8, assembleRelease |
| `./gradlew :app:testDebugUnitTest spotlessCheck :app:detekt :app:lintDebug` | Tier 1 without R8 |
| `./gradlew :app:koverLog` | Coverage log (floor 15; do not raise casually) |
| `./gradlew :app:connectedDebugAndroidTest -PabiFilters=x86_64 "-Pandroid.testInstrumentationRunnerArguments.notPackage=com.indicvision.semper.benchmark"` | Emulator instrumented; exclude benchmark package on debug |
| `cd backend && python -m pytest tests/ -q --cov=app --cov-fail-under=75` | Backend (install lock + `requirements-test.txt`) |
| `python scripts/render_legal_pages.py --check` | Hosted legal pages match `docs/legal/` |
| `gitleaks detect --config .gitleaks.toml` | Secret scan |

Clone needs `git submodule update --init --recursive`. First native build compiles
OpenCV; cache is `app/.cxx/` (safe to delete).

Engine tests are **not** this CI. From the submodule: see [docs/engine/TESTING.md](docs/engine/TESTING.md).

## Where to edit

| Change | Start |
|--------|-------|
| Wizard chrome / steps | `StaticAnalysisActivity.goToStep`; slots/coach if present |
| Full-field batch / `.dat` write | `DicBatchRunner` + `DicFieldIo`, else `AnalysisViewModel` |
| Viewer / exports | `ResultViewerActivity`, `ShareCenter` |
| Settings sections | `Settings*Section`; restore/delete stay on `SettingsActivity` |
| Session paths | `SessionPaths` only |
| Backend routes | `backend/app/routers/`; app/middleware in `main.py` |
| Engine math / solver | `native/` submodule + [docs/engine/ENGINE_APP_CONTRACT.md](docs/engine/ENGINE_APP_CONTRACT.md) |

## Guardrails

- Empty lint/detekt baselines. Extract or `@file:Suppress`; do not stuff findings.
- Bit-exact `.dat` / GIF oracles. JNI `computeFullFieldDirect` stays in the one batch loop.
- Do not split VisualizationEngine, GifEncoder LZW, ReportBuilder, upload `doWork`, scrub cache, `PointSpatialIndex.build`.
- Do not bump `targetSdk` 36→37 or enable `warningsAsErrors` without an explicit decision.
- Never bump `backend/requirements.txt` without `pip-compile --generate-hashes` on Python **3.12**.
- Never commit `local.properties` (API URL) or keystores.
- PRs target `main`. No force-push to `main`. No `--no-verify`.

## Docs to update in the same PR

Auth, quotas, deploy env, CI modes, or architecture extracts → matching file under
`docs/` (see [docs/README.md](docs/README.md)). Then refresh the **Current state**
section of CONTEXT.md if the branch/PR picture changed.
