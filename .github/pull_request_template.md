## What does this PR do?

<!-- One or two sentences. Link the issue it closes, e.g. "Closes #12". -->

## Checklist

- [ ] Builds locally (`./gradlew :app:compileDebugKotlin`)
- [ ] If bumping `engine/` engine pin: engine CI green on [semper-dic-engine](https://github.com/sempermechanics/semper-dic-engine)
- [ ] Tests pass; new behavior has a test
- [ ] `./gradlew spotlessApply` run (CI enforces formatting)
- [ ] No hardcoded user-facing strings (use `strings.xml`)
- [ ] For engine changes: results still within the tolerance contract
      (see docs/engine/TESTING.md) — say so explicitly if they shifted
- [ ] Backend API: `backend/contracts/app-openapi.json` changed? **Breaking per
      ADR-029 (`docs/adr/ADR-029-api-contract-snapshot.md`): yes / no.** If yes,
      the lines are declared in `backend/contracts/app-openapi.breaking.txt` and
      the client change ships first (or a compatibility window is stated here)
