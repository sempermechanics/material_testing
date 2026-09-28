# ADR-012: Tensile strain is the virtual extensometer's ΔL / L₀

**Status:** Accepted, built
**Date:** 2026-09-28
**Deciders:** app owner

## Context

The lab wants 2D DIC to replace the clip-on extensometer in the tensile test,
so the machine log supplies only load and time, and the app supplies strain.

The curve's strain was the mean of the load-axis strain component (Exx or Eyy)
over every accepted point of the analysed region
(`StressStrain.Model.Axial.strainMilli` at `7f32583`). An extensometer reads
something else: the engineering strain e = ΔL / L₀ between two fixed points.
ISO 6892-1 and ASTM E8 define elongation this way. The two readings agree
while the strain is uniform, and part once it is not:

- **After necking starts** (past the peak load), the region mean depends on how
  much of the region the neck covers, so it depends on the ROI a student drew.
  Elongation after the peak is not comparable with an extensometer's.
- **The strain measure.** The engine's Exx / Eyy is a field derivative; which
  finite-strain tensor it is (small strain or Green–Lagrange) is not stated
  in the engine contract (`docs/engine/ENGINE_APP_CONTRACT.md` §A.4). At a
  20 % elongation Green–Lagrange reads about 10 % above engineering strain
  (E = e + e²/2). ΔL / L₀ from displacements is engineering strain whatever
  the engine computes.
- **On published steel data** the region mean read 7.8 % above the dataset's
  gauge points over frames 13–28 ([REAL_WORLD_VALIDATION.md](../app/REAL_WORLD_VALIDATION.md)).

The app already had a virtual extensometer (`report/Extensometer.kt`, CHANGELOG
2026-09-21 → 2026-09-24): two end bands, each a tenth of the region along the axis, fixed
on the first solved frame; ΔL is the far band's mean displacement along the
axis minus the near band's. It filled only the lab table's **Extension (px)**
column. On the steel case ΔL ÷ gauge tracked the plotted strain within 0.5 %
in the elastic range (1.95×10⁻³ against 1.94×10⁻³ at 263 MPa,
[STUDENT_LAB_WORKFLOW.md](../app/STUDENT_LAB_WORKFLOW.md)).

## Decision

The tensile strain (`StressStrain.Model.Axial`) is the virtual extensometer's
**ΔL / L₀**, in millistrain:

- `Model.strainMilli(data, gauge)` takes the curve's gauge. `Axial` returns
  `gauge.extensionPx(data) / gauge.lengthPx · 1000`; `Flexural` ignores the gauge
  and keeps the mean over accepted points.
- The gauge is fixed on the first frame, with a load and a field, that can set
  one. A frame where either band has no accepted point has **no strain and no
  point on the curve**, rather than a strain read over a different length.
- `StressStrain.Collector` builds the points one frame at a time. `build`
  (viewer, share, PDF) and the streaming CSV writer both use it, so the curve
  and the CSV's `# mechanical_results` trailer cannot disagree.
- The wording follows: `strainName` is "ΔL/L₀ along x|y"; the lab report says
  "2D DIC virtual extensometer, … between the ends of the analysed region";
  the PDF curve page says "… between the end bands of the analysed region".

Pixels cancel in the ratio, so no mm scale is needed. L₀ is set by the ROI:
the distance between the bands' mean positions, about 0.9 of the accepted
points' extent along the axis.

Bending is unchanged: the camera sees the outer fibre, and the mean strain
there is the flexural strain.

## Options considered

### A: Keep the region mean

**Cons:** not what an extensometer measures; it depends on the ROI after
necking, and the measure depends on the engine's strain tensor.

### B: ΔL / L₀ between end bands the ROI sets (chosen)

| Dimension | Assessment |
|-----------|------------|
| Complexity | Low: the gauge and ΔL existed; the curve reads them |
| Cost | Two band passes per frame instead of one mean |
| Risk | Noisier than the whole-field mean; a band that stops correlating ends the curve |

### C: A gauge length the student sets in mm (e.g. L₀ = 5.65 √S₀)

**Pros:** matches the standard gauge exactly.
**Cons:** tensile has no mm-per-px scale; it needs a calibration step (a
target of known size, or the specimen's width) and UI to place the gauge
marks. Deferred, not rejected.

### D: Plot both, or a toggle

**Cons:** two strains for one E; the student has to know which the standard
means. Rejected.

## Trade-offs

- **Noise.** ΔL averages two bands of about a tenth of the points each, not the
  whole region. In the elastic range, where steel stays below 2 mε, E becomes
  more sensitive to the camera's noise. The fit (`ElasticModulus.fit`) is
  unchanged.
- **Rigid rotation.** ΔL is taken along the image axis. A small in-plane
  rotation of the specimen adds second-order terms (1 − cos θ) and is ignored.
- **L₀ is the ROI's.** Two students who draw different ROIs on the same run
  get the same elastic E but different elongation after necking, as two
  extensometers of different gauge lengths would.
- **End of the curve.** When a band leaves the view or stops correlating
  (usually well past the peak), the curve stops there. The old mean went on
  over whichever points remained.

## Consequences

- The curve is not stored; the viewer, share, PDF and CSV rebuild it from the
  `.dat` files. Existing tensile sessions therefore show the new strain and a
  new E the next time they are opened or exported. No migration.
- The CSV format does not change: its point rows carry the field (per-point
  Exx / Eyy), load and stress, never the curve's strain. The value of the
  `# elastic_modulus_gpa` trailer changes.
- The real-world numbers in
  [REAL_WORLD_VALIDATION.md](../app/REAL_WORLD_VALIDATION.md) were taken with the
  region mean. They are owed a re-run (TD-142).
- `.dat` and GIF oracles are untouched: this is downstream of the solve.

## Action items

- [x] `Axial` strain from the gauge, `StressStrain.Collector`, CSV writer on the
      collector, report wording, tests (`StressStrainTest`, `ExtensometerTest`,
      `AnalysisCsvPreambleTest`); device-test and benchmark seeds stretch u.
- [ ] TD-142: re-run the steel validation under ΔL / L₀ and replace the table.
- [ ] Option C when a tensile mm scale exists.
