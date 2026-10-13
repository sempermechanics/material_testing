# ADR-011: The viewer draws each frame on its own photo

**Status:** Accepted, built
**Date:** 2026-09-28
**Deciders:** app owner

## Context

The result viewer decoded the reference photo once
(`ResultViewerActivity.decodeReferenceForDisplay`) and drew every frame's
heatmap on it, at the points' reference positions (x, y). This is the
Lagrangian view and had been the design since the viewer overhaul (`0ced55e3`).

For the lab tests it reads as a bug. A student scrubbing a tensile or bending
run sees the same photo under every frame: the bar never stretches and the
beam never bends, though the map says they did. The report already used each
frame's own photo on its page (`ViewerReportFactory.buildReportData`); the
screen did not.

Putting frame N's photo behind the old map is not enough. The map would sit
where the points *were*, off the specimen by (u, v), which is tens of pixels
at a bending beam's midspan.

## Decision

On screen, each frame is drawn in the **deformed configuration**, on that
frame's own photo:

- `VisualizationEngine.generateDeformedHeatmap` / `…Indices` draws each grid
  cell as the quad through its corners' displaced positions (x + u, y + v),
  filled by inverse-bilinear interpolation. With no displacement it is the
  reference render, within one colour step. The colour range is the same.
- `ResultViewerActivity` looks up each frame's photo with `deformedImagePathAt`,
  off the main thread in `readFrameDat`, and decodes it at display size under
  the map (`showFrameBase`). A frame whose photo is not on disk, or does not
  decode, falls back to the reference and the reference-position map, so the
  photo and the map always match.
- Tap-to-probe looks up the nearest **moved** point
  (`ViewerInspectController.displacedPositions` into the unchanged
  `PointSpatialIndex.build`), and the crosshair sits there. The readout still
  gives the point's reference (x, y), which is how the CSV names it.
- The rest-fit box also covers where the points moved to.
- A sweep shows its one deformed photo under every node.

`generateHeatmapIndices`, which fed the summary GIF and the report, was left
unchanged here; the GIF bytes stayed pinned. TD-139 (2026-10-13) then moved
the exports onto the screen's picture: see As built.

## Options considered

### A: Keep the reference under every frame

**Cons:** the complaint stands. The screen disagrees with the report.

### B: Frame photo behind the unchanged map

**Cons:** the map is offset from the specimen by the displacement. That is
wrong, and it looks more wrong than A.

### C: Deformed configuration on the frame photo, everywhere on screen (chosen)

| Dimension | Assessment |
|-----------|------------|
| Complexity | Medium: a second rasterizer, a per-frame photo decode, a displaced probe lookup |
| Cost | One display-size decode per settled frame, off the main thread |
| Risk | The GIF and report still drew on the reference (TD-139, closed 2026-10-13) |

### D: A viewer toggle, reference by default

**Cons:** the student has to find a setting to see the specimen deform.
Rejected by the app owner.

## Trade-offs

- Scrubbing decodes a photo per settled frame (debounced like the `.dat`
  load). For a moment after a step, the new map can sit on the previous
  frame's photo.
- Coordinates in the probe readout are reference positions. The crosshair is
  not.

## Consequences

- As first built, the summary GIF (`SummaryAnimation.kt:249`), the PNG export
  (`ShareCenter.kt:388` in material_testing; `FieldImageExport.kt:52` here) and
  the report heatmap (`ReportBuilder.kt:209`) still drew on the reference.
  TD-139; closed by the As-built note below.
- Restored sessions need the deformed originals to show their frames this
  way. They already restore them (`docs/perf/backup-restore-split.md`).

## Action items

- [x] Deformed render, per-frame photo, displaced probe, fit box, tests
      (`DeformedHeatmapTest`).
- [x] TD-139: decide whether the GIF and exports follow. The app owner decided
      all three follow the screen (2026-10-13).

## As built (TD-139, 2026-10-13)

Every export of a frame draws what the viewer draws for it.

- `VisualizationEngine.generateFrameHeatmap(displaced, …)`
  (`report/VisualizationEngine.kt:169`) is the one choice between the
  deformed and the reference render. The viewer (`ViewerScaleController`), the
  PNG export and the report's field images all call it.
- PNG export: `FieldImageExport.renderAnnotated`
  (`ui/viewer/share/FieldImageExport.kt:58`) loads the frame's base first
  (`loadFrameBase`, `:132`): the frame's own photo, looked up by
  `ViewerReportFactory.Source.deformedImagePathAt` (the viewer's
  `deformedImagePath`) and decoded capped with `BitmapDecoder.decodeFileForView`
  as the viewer decodes it, else the reference. `FrameBaseCache` keeps one
  frame's base for its fields and is freed before the next frame, so the
  everything ZIP holds one photo at a time.
- Report: `ReportBuildParams.fieldsOnFramePhoto` (set through
  `ReportSource.forFrame`'s `onFramePhoto` at `ViewerReportFactory.kt:125` when
  the frame's photo decoded, and at `SessionUploadBundler.kt:386` likewise for
  the cloud bundle's PDFs and `processed/` PNGs) makes the fusion
  pass draw each field on `defImgForCover` with the displaced render
  (`report/ReportBuilder.kt:148`, `:222`). The loop body stays whole; only what
  it samples changed.
- MAX / MIN markers go where their points moved to on a frame's photo
  (`FieldAnnotation.displaced`, `report/ReportAnnotations.kt:99`), as the
  viewer's crosshair does. The printed coordinates stay the reference ones,
  as in the probe readout and the CSV.
- Summary GIF: `SummaryAnimation.renderFrame`
  (`ui/viewer/summary/SummaryAnimation.kt:268`) draws every frame with
  `generateDeformedHeatmapIndices`. The GIF carries no photo (its palette is
  the jet ramp plus the canvas colour, so nothing is quantised), so it is
  displaced whether or not a frame's photo is on disk. Its box
  (`fitBoundsForEncode`, `:230`) is the viewer's rest box widened to every
  frame's displaced bounds and cut to the image. The viewer's summary slot
  shows this same GIF, so it and the export cannot disagree.
- `oracles/summary_u.gif` and `oracles/summary_exx.gif` were regenerated with
  `-PupdateGoldens`: `summary_u.gif` `ffb9838b…0abc` → `73b64655…d0e4`,
  `summary_exx.gif` `61fb73af…93ce` → `099040c2…fbe4` (SHA-256). The `.dat`
  oracle is unchanged.
- Cost per exported frame: one capped decode of the frame's photo (shared by
  its fields); the deformed render in place of the reference one. The GIF
  reads each `.dat` once more, up front, for its box. Re-run on the device:
  `HotPathMicroBenchmark` (`buildReport_oneFrame`,
  `generateDeformedHeatmap_oneFrame`, which is now each exported field's
  render, `gifEncode_10frames` / `gifEncode_150frames`, `decodeDatFile_oneFrame`)
  and `ViewerScrubBenchmark` (the viewer now renders through
  `generateFrameHeatmap`). `HotPathMicroBenchmark` keeps to signatures on
  `main`, so it times the reference report path, not the new on-photo one.
- Tests: `FieldImageExportTest`, `ReportDeformedFrameTest`,
  `SummaryGifDeformedTest`, `DeformedHeatmapTest`.
