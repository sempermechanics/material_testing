## What does this PR do?

<!-- One or two sentences. Link the issue it closes, e.g. "Closes #12". -->

## Checklist

- [ ] Builds locally (`./gradlew :app:compileDebugKotlin` and/or `dic_tests`)
- [ ] Tests pass; new behavior has a test
- [ ] `./gradlew spotlessApply` run (CI enforces formatting)
- [ ] No hardcoded user-facing strings (use `strings.xml`)
- [ ] For engine changes: results still within the tolerance contract
      (see docs/engine/TESTING.md) — say so explicitly if they shifted
