# ADR-004: Immutable `RunSpec` built once at Compute

**Status:** Accepted
**Date:** 2026-09-23
**Deciders:** app owner

## Context

"What settings did this run actually use?" has no single answer until
`SessionRepository.buildSessionRecord` assembles one at commit. Before that the
answer is spread across:

- 47 public `var`s on `AnalysisViewModel` (inputs, results, editing state and
  session identity mixed together; `hasCompletedAnalysis` is written and never
  read);
- five slider/radio views on `StaticAnalysisActivity` (1,827 lines) — subset,
  step, overlap, strain window and interpolator exist *only* in views and are
  read once when `BatchAnalysisParams` / `SweepRequest` are built
  (`StaticAnalysisActivity.kt:1288-1328`, `:1545-1559`);
- a ROI that exists twice: the requested one on the ViewModel and the resolved,
  inset one in `BatchAnalysisParams.finalRect*` (`RoiResolveHelper.resolve`).

The fault this causes is TD-61: `AnalysisNavHelper.openResults` reads the
ViewModel after the run, so it passes the *requested* ROI, the slider values
at the moment of opening, and `last*` fields that a sweep path never resets.
The saved session carries the resolved ROI and the run's real values, so the
viewer's metadata depends on how it was opened. Separately, the sweep path
reads `roiMaskBytes` directly from the ViewModel mid-run
(`AnalysisViewModel.kt:450`) rather than from its request.

Constraint: the JNI `computeFullFieldDirect` call stays inside the one batch
loop in `DicBatchRunner.kt`, and `.dat` output is bit-exact against oracles.

## Decision

Introduce `RunSpec`, an immutable data class snapshotted once when the user
taps Compute:

```kotlin
data class RunSpec(
    val localSessionId: String,
    val ref: RefSpec,                    // name, width, height, bytes handle
    val frames: List<FrameSpec>,         // path, original name
    val roiRequested: Roi,
    val roiResolved: Roi,                // RoiResolveHelper.resolve output
    val maskBytes: ByteArray?,
    val subset: Int, val step: Int, val strainWindow: Int,
    val overlap: Double, val use6x6: Boolean,
    val sweep: SweepSpec?,               // plan, lineCutHorizontal, vsgFrameIndex
)
```

- `BatchAnalysisParams` and `SweepRequest` are **derived** from it; the engine
  receives exactly what it receives today.
- `RunResult` carries the `RunSpec`; `AnalysisNavHelper` and
  `buildSessionRecord` read the spec, never the ViewModel's editing fields.
- Removed: `hasCompletedAnalysis`, the nav-only `last*` reads, the sweep's
  direct `roiMaskBytes` read.
- The wizard keeps its mutable editing state on the ViewModel.

## Options considered

### A: `RunSpec` snapshot (chosen)

| Dimension | Assessment |
|-----------|------------|
| Complexity | Medium |
| Cost | ViewModel, `DicBatchRunner.kt`, sweep body, nav helper, record builder |
| Scalability | New run parameters get one home |
| Team familiarity | Plain Kotlin data class, like `BatchAnalysisParams` |

**Pros:** fixes TD-61 at the source; the engine's inputs are unchanged by
construction. **Cons:** one more type between the UI and the engine.

### B: Hoist all wizard state into `StateFlow` UI state

**Cons:** a rewrite of a 1,800-line native-solve screen to fix a fault that
needs only the run's inputs frozen. Kept as a possible later step (see
ADR-005 for the part of it that process death needs).

### C: Status quo, patch `AnalysisNavHelper` to read `params.finalRect*`

**Cons:** fixes the ROI only; subset/strain window and stale `last*` stay
wrong, and the next field added repeats the fault.

## Trade-off analysis

A is the smallest change that makes "the settings this run used" a value
instead of a reconstruction. Bit-exactness is protected because params are
derived field for field; the proof is mechanical (identical `.dat` hashes).

## Consequences

- Easier: the viewer, report, CSV and cloud metadata all read one value.
- Harder: nothing may mutate a run's inputs after Compute (intended).
- Revisit: if the wizard grows further, ADR-005's draft plus `RunSpec` make a
  full UI-state hoist cheap.

## Action items

1. [ ] `RunSpec` + builders; params derived from it.
2. [ ] `RunResult.spec`; nav helper and record builder read it.
3. [ ] Remove `hasCompletedAnalysis`, nav `last*` reads, the sweep's direct
       mask read.
4. [ ] Unit tests: params derived from a spec are field-equal to today's.
5. [ ] Emulator: same fixture before/after, SHA-256 of every
       `frame_%04d.dat` identical; `EnginePipelineSmokeTest` passes.
