# Student lab workflow — tensile and bending with a phone

Product spec for the direction this repository is taking: **Material Testing
is a phone-camera 2D DIC companion for a first-semester undergraduate** doing
the two standard strength-of-materials experiments. The phone replaces the
contact instrument (extensometer, dial gauge) and the app writes the lab
report the student would otherwise draw up by hand.

The two reference write-ups this spec was built from are a student's
handwritten journal reports: *Experiment 2 — Measurement of Tensile Strains
and Modulus of Elasticity* and *Experiment 5 — Measurement of Bending Moment
and Deflection of Beam*. Their sections, order and outputs are what the app
reproduces.

## Who it is for

A student in the first semester of an engineering degree:

- has met stress, strain and Hooke's law, but not DIC;
- runs the test in a university lab (a UTM for tensile, a loading frame with a
  hanger for bending) and reads loads off the machine or counts the weights;
- has to hand in a journal write-up: aim, apparatus, theory, observations, an
  observation table, a worked calculation, graphs and results.

So the copy is plain, formulas are spelled out, and every result the app
shows is one the write-up asks for.

## What the student gives the app

| | Tensile | Bending |
|---|---|---|
| Photos | Reference before loading; one photo per load step, speckled face towards the camera | Reference when the dial would be zeroed (no weights); one photo per weight added; camera on the **side face** of the beam |
| Loads | CSV, N or kN, one per photo in photo order (or the machine's log) | CSV, N or kN — hanger weights × 9.81 (kg is not read) |
| Dimensions | Cross-section area (mm²) — for a round bar π·d²/4 | Span L, width b, thickness t (mm) |
| Scale | None — strain is a ratio | The beam's top and bottom edge tapped on the reference photo *(next PR)* |

Loads stay **CSV-only** for now. Typing a load per photo in the app, and how a
photo is paired with its load (sync), are open decisions.

## What the app gives back

### Tensile — Experiment 2

- **Stress–strain curve**: σ = P/A per photo, ε = the DIC strain along the
  load axis, averaged over the analysed region.
- **Young's modulus E**: the slope of the straight early part of the curve.
  The app fits the longest leading run of photos, up to the peak, whose
  straight line keeps R² ≥ 0.995, with a free intercept. Stress in MPa over
  strain in mε is E in GPa directly.
- **Peak (ultimate) stress**.

The unloaded reference is *not* forced onto the line: labs zero the gauge
under a small preload, and the Experiment 2 table then reads 218 GPa through
the origin instead of 194 GPa. E from a phone is approximate and every
surface says so.

### Bending — Experiment 5 *(next PR)*

- **Bending stress** σb = M·y/I with M = WL/4, y = t/2, I = bt³/12 — the
  same number as the app's existing three-point flexural stress 3PL/(2bh²).
- **Deflection δ** at the load point, from DIC in place of the dial gauge.
- **E from deflection** E = WL³/(48·δ·I), per load step and averaged, and
  from the slope of the load–deflection line (the report asks for both).

## Where the results appear

- **Viewer → Results**: the page the viewer opens on (the summary slot before
  frame 1) — the curve with the fitted line, E with the photos it came from,
  peak stress. The same block closes the ⓘ sheet. The heatmap animation that
  slot shows for other sessions is still under Share → Animations.
- **Share → Lab report (PDF)**: the write-up in the handwritten report's own
  layout, filled with the session's data. Tensile sections, in order:
  experiment and title, Aim, Materials required, Theory, figure (the
  reference photo with the analysed region, where the report has its UTM
  sketch), Observations, observation table (S.No, Load, Extension, Stress,
  Strain, with Elastic / Plastic / Break point bracketed in the margin),
  Calculation (row 1 worked through), graphs (elastic region with its line,
  full curve with E boxed), Results, and ruled lines for the student's own
  Conclusions. What the app cannot know — total length, gauge length,
  diameter, final diameter and gauge length, extension in mm — stays as a
  blank line to fill in by hand.
- **CSV**: a `# mechanical_results` trailer after the point rows
  (`# elastic_modulus_gpa`, `# elastic_fit_frames`, `# elastic_fit_r2`).
- The full DIC **PDF report** keeps its stress–strain page, now with E.

## Capture checklist

1. Speckle the face the camera sees (tensile: the bar's face; bending: the
   beam's side face).
2. Phone on a tripod or clamp; it must not move between photos.
3. Reference photo before any load (tensile) or before the first weight
   (bending).
4. Several photos in the straight, elastic part — at least three, more is
   better — before the specimen yields.
5. Loads in a CSV, one row per photo, same order.

## Worked numbers

**Experiment 2.** d = 12.54 mm, A = 123.51 mm². Row 1: 8.9 kN → 72.06 MPa,
strain 0.002/25 = 8×10⁻⁵. All twelve rows form one straight run:
**E = 194.0 GPa**, R² 0.9989. The handwritten report quotes ~170 GPa from a
hand-drawn slope; the table itself fits to 194 GPa.

**Experiment 5.** L = 935 mm, b = 150 mm, t = 6.38 mm, I = 3.246×10³ mm⁴
(3.246×10⁻⁹ m⁴). Step 1, W = 4.28 kg: σb = 9.65 MPa and E = 193.2 GPa. The
report's 9.591 MPa and 189.94 GPa come out of L = 930 mm with g ≈ 9.8. The
average over the steps is ≈ 173.6 GPa; the load–deflection slope,
27.06 N/mm, gives ≈ 142 GPa.

## Accuracy

- Camera strain is noisier than an extensometer's; E is approximate. Small
  strains (the elastic range of steel is below 1 mε) are where DIC noise
  matters most — more pixels on the specimen and a still camera help most.
- Strain is the mean over the analysed region; after necking it understates
  the local strain (a virtual extensometer is not built yet).
- A slack or seating start can break the leading-run rule and give no E or a
  short run; the report then says so.

## Deferred

- Typing loads per photo in the app; the photo↔load sync strategy.
- Comparison against a textbook or lab-provided E.
- Virtual-extensometer strain over a chosen gauge length.
- Automatic edge snapping for the bending thickness tap.
