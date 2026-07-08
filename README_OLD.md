
<div style="text-align: center;">

<p align="center">
  <img src="images/inDIC readme Images/Gemini_Generated_Image_kr4qadkr4qadkr4q.png" width="300" alt="Main Analysis Interface">
</p>

# inDIC
### *2D Digital Image Correlation. Natively on Android.*

<img src="https://img.shields.io/badge/build-passing-brightgreen?style=flat-square" alt="Build"> <img src="https://img.shields.io/badge/platform-Android%20(all%20ABIs)-blue?style=flat-square&logo=android" alt="Platform"> <img src="https://img.shields.io/badge/language-Kotlin%20%7C%20C%2B%2B17-orange?style=flat-square" alt="Language"> <img src="https://img.shields.io/badge/NDK-C%2B%2B%20Engine-red?style=flat-square" alt="NDK"> <img src="https://img.shields.io/badge/math-Eigen%203.4-purple?style=flat-square" alt="Eigen"> <img src="https://img.shields.io/badge/vision-OpenCV%204.x-green?style=flat-square" alt="OpenCV"> <img src="https://img.shields.io/badge/license-Proprietary-lightgrey?style=flat-square" alt="License"> <img src="https://img.shields.io/badge/RMSE-0.0078%20px-brightgreen?style=flat-square" alt="RMSE">

> **inDIC** is the first offline-first, full-field 2D Digital Image Correlation platform engineered natively for Android — delivering sub-pixel displacement and Green–Lagrange strain metrology entirely on-device, without any cloud dependency.

</div>

---

## Table of Contents

1. [The Story — Why inDIC Exists](#1-the-story--why-indic-exists)
2. [Key Innovations Under the Hood](#2-key-innovations-under-the-hood)
3. [App Features](#3-app-features)
4. [Step-by-Step User Guide](#4-step-by-step-user-guide)
5. [Installation & Build Instructions](#5-installation--build-instructions)
6. [Performance & Validation](#6-performance--validation)
7. [Architecture Overview](#7-architecture-overview)
8. [Security Model](#8-security-model)
9. [Roadmap](#9-roadmap)
10. [Acknowledgements](#10-acknowledgements)

---

## 1. The Story — Why inDIC Exists

### The Problem: DIC is Locked to the Lab

Digital Image Correlation (DIC) is the gold standard for full-field, non-contact surface strain measurement in experimental mechanics. It is used everywhere — from structural testing in aerospace to biomechanics research to civil infrastructure monitoring. But for decades, DIC has been anchored to an expensive, immovable constraint: **it requires a desktop PC**.

Every professional DIC package — Ncorr, ALDIC, DICe (Sandia National Labs), Vic-2D — assumes you have access to:

- A lab-grade workstation with gigabytes of RAM
- The Trilinos HPC solver stack or equivalent numerical libraries
- A quiet, controlled environment where you can bring the specimen to the computer

This means **zero** possibility of real-time, on-site measurement at a construction site, a bridge inspection, a field test in the desert, or a student lab without a licensed workstation. A structural engineer cannot pull out their phone and measure strain on a cracking beam. A student cannot afford the tools to even experiment with DIC fundamentals.

### The HPC Wall

The core computational challenge is not simply porting code — it is that the algorithms which make DIC accurate were *designed* to be computationally dense. The **Inverse Compositional Gauss-Newton (ICGN)** solver, which is the heart of all precision DIC engines, must iterate a 6×6 matrix inversion per subset per frame. Solve 78,000 subsets at 50 iterations each and you have a number that will exceed the CPU timeout budget of any mobile scheduler, crashing the thread silently.

Beyond that, the **Trilinos** solver library that powers DICe's domain-decomposition does not exist on Android. There is no `apt-get install trilinos` for ARM64. Re-engineering the entire numerical foundation from scratch — without HPC libraries — is the core engineering challenge this project solves.

### The inDIC Solution

inDIC conquers the HPC Wall through three interlocking innovations:

1. **Pre-inverted Hessian Pool** — The expensive 6×6 Hessian matrix is computed and inverted *once* for the entire ROI in a parallel pre-pass, then reused across all solver calls. This converts a per-iteration \\(\mathcal{O}(N \cdot 36)\\) operation into a \\(\mathcal{O}(1)\\) lookup.
2. **Hybrid Delaunay-ICGN Architecture** — A sparse AKAZE feature mesh provides exact 6-DOF affine initial guesses, collapsing ICGN convergence from 20–50 iterations to 3–7 iterations per subset on average.
3. **Portable SIMD (NEON + SSE)** — The inner ICGN loop is vectorised through OpenCV universal intrinsics, so the ZNSSD error and gradient accumulation compile to NEON on ARM and SSE on x86 from a single codepath. The engine ships for **all Android ABIs** (arm64-v8a, armeabi-v7a, x86, x86_64), so it runs natively on physical devices *and* emulators.

The result: **78,000 point full-field DIC in 4.6 seconds** on a consumer Android smartphone, with RMSE accuracy of 0.0078 px — matching Sandia National Labs' desktop software (DICe) on the standard DIC Challenge benchmark.

---

## 2. Key Innovations Under the Hood

### 2.1 Hybrid Delaunay 6-DOF Mesh Seeding
**Files:** `IndicVisionJNI.cpp` (PATH A block), `RoiDrawActivity.kt`

Most mobile DIC attempts fail because they seed ICGN with a zero initial guess, forcing the solver to explore the entire 6-DOF parameter space from scratch. inDIC instead uses **AKAZE + RANSAC feature matching** to extract a sparse set of geometrically verified correspondences, then builds a **Bowyer-Watson Delaunay triangulation** over those points. Each triangle carries an exact affine warp computed via `cv::getAffineTransform` — giving every grid point a 6-DOF initial guess \\([u, v, u_x, u_y, v_x, v_y]\\) before a single ICGN iteration begins.

This reduces mean iteration count from ~35 to **3–7 iterations per point** — the single largest performance improvement in the entire pipeline.

### 2.2 Global Shared-Queue RGDIC (Reliability-Guided DIC)
**Files:** `IndicVisionJNI.cpp` (PATH B block), `Types.h` (`SeedNode`)

Points not covered by the Delaunay mesh (boundary regions, feature-sparse zones) are solved by a **concurrent priority-queue flood-fill** (RGDIC). A `std::priority_queue<SeedNode>` is shared across all worker threads, ordered by ZNSSD correlation quality. The most reliable solved point always propagates to its 4-connected neighbours first, exploiting displacement field continuity to seed neighbours without requiring a new initial search. Thread safety is maintained via `std::atomic<bool>` cell-claim flags — eliminating mutex contention over the grid.

### 2.3 Pre-Computed Hessian Pool (OMP Parallel Pre-Pass)
**Files:** `SubsetPrecomputer.cpp`, `SubsetPrecomputer.h`

The ICGN Hessian:

$$\mathbf{H}=\sum_{i=0}^{N-1}\mathbf{s}_i\mathbf{s}_i^T$$

depends *only* on the reference image and is constant across all deformed frames. inDIC pre-computes and caches $\mathbf{H}$ and $\mathbf{H}^{-1}$ for every grid point across all CPU cores in a single `#pragma omp parallel for` pass before any solver is dispatched. The fast path `precompute_subset_fast()` then skips the Hessian accumulation entirely (saving ~65% of precompute FP work per call), using `memcpy` block transfers for intensity/gradient data and a fused single-pass loop for SDI construction and intensity normalization.

### 2.4 Keys' 4th-Order Bicubic Interpolation
**Files:** `ImageProcessor.cpp` (`get_keys_weights`, `Image::interpolate_bicubic`)

Sub-pixel intensity sampling uses the **Keys cubic convolution kernel** (\\(a=-0.5\\)), which achieves 4th-order approximation accuracy — the highest commonly used in DIC literature. The implementation is fully separable (row-then-column), branch-free, and operates on a 4×4 pixel neighbourhood. The preceding gradient map uses a **4th-order central difference stencil** (\\(\mathcal{O}(h^4)\\)) rather than the standard 2nd-order stencil, improving gradient accuracy at the cost of a 2-pixel boundary guard.

### 2.5 Levenberg-Marquardt Regularisation (Selective Damping)
**Files:** `OptimizationEngine.cpp` (`solve_icgn`), `OptimizationEngine.h`

For subsets with poor texture or large initial displacement errors, a **Levenberg-Marquardt damping** scheme can be enabled per-analysis. Following DICe's `computeUpdateFast` strategy, only the two translation DOF diagonal entries \\(H_{00}\\) and \\(H_{11}\\) are damped — the four strain-gradient DOFs are left undamped. This preserves the IC-GN convergence rate on well-textured regions while regularising translation-degenerate cases. Because \\(\alpha_{LM}\\) is a fixed scalar, the damped inversion is performed once per solver call, not per iteration.

### 2.6 Green–Lagrange Finite Strain Tensor + Dual Strain Engines
**Files:** `StrainCalculator.cpp`

Both strain calculation methods compute the full Green–Lagrange tensor — not the small-strain engineering approximation:

$$E_{xx}=u_{,x}+\frac{1}{2}(u_{,x}^2+v_{,x}^2)$$

- **VSG (Virtual Strain Gauge):** Fits a linear displacement plane to all neighbours within a circular window via LDLT least-squares. Equivalent to DICe's standard method.
- **NLVC (Non-Local Virtual Compressor):** Uses a Gaussian derivative kernel over a circular horizon, providing smooth, noise-robust strain estimates with configurable spatial localisation.

### 2.7 Portable SIMD Vectorisation (NEON + SSE)
**Files:** `core/SimdKernels.h`, `OptimizationEngine.cpp` (fast-path in `solve_icgn`), `SubsetPrecomputer.cpp` (`sdi_planes`)

The inner ZNSSD accumulation loop is written **once** using OpenCV universal intrinsics (`cv::v_float32`) in `SimdKernels.h`, which the compiler maps to NEON on ARM and SSE on x86 — replacing the previous hand-written `#ifdef __aarch64__` NEON blocks. Mean subtraction, standard-deviation computation, the error image, and the 6-DOF steepest-descent projection are all vectorised. To vectorise the gradient projection, the steepest-descent images are stored in a Structure-of-Arrays mirror (`sdi_planes`) so all six components stream contiguously. The scalar tail is handled automatically, and a plain scalar path remains for any target without SIMD. This makes the engine architecture-independent while keeping (and, on the gradient loop, improving) the original ARM performance. See [`docs/ARCHITECTURE.md`](docs/ARCHITECTURE.md) and the `SimdKernels` test suite in [`docs/TESTING.md`](docs/TESTING.md).

### 2.8 JNI Thread Pinning
**File:** `AnalysisViewModel.kt`

The entire native execution path is pinned to a **single persistent OS thread** (`IndicVision-NativeThread`) via a `newSingleThreadExecutor`. The LLVM OpenMP runtime registers its master thread in TLS on the first `#pragma omp parallel` call. If a subsequent frame arrives on a different OS thread, OpenMP's TLS lookup returns null and the process terminates with SIGSEGV. Thread pinning eliminates this class of crash entirely.

### 2.9 Jet Colormap LUT with Bilinear Grid Interpolation
**File:** `VisualizationEngine.kt`

Result heatmaps are rendered using a **pre-computed 256-entry Jet LUT** (built once at class load). Grid point values are upsampled to full image resolution via bilinear interpolation between adjacent grid cells, producing smooth, continuous colour fields rather than blocky nearest-neighbour maps. Scale bounds use robust **2%–98% percentile clipping** with a minimum span floor to prevent noise-floor explosion on near-uniform fields.

---

## 3. App Features

### 📷 Image & Video Input
- Load reference and deformed images from device gallery (single or batch up to N frames)
- Supports PNG, TIFF, JPEG (with JPEG artifact warning), DNG/RAW
- **Load from Video** — pick a clip and the app auto-extracts frames: the first frame of the chosen segment becomes the reference and the rest become the deformed sequence. A sampling dialog shows the video's resolution, frame rate and length (from metadata) and lets you choose the **extraction frame rate** and **time segment** to sample.
- Optional 7-tap Gaussian pre-filter (toggleable per-analysis)
- Native C++ image dimension detection without full decode

### 🎯 Region of Interest (ROI) Definition
- **Draw ROI** — Full-screen interactive canvas with 5 shape modes: Rectangle, Square, Circle, Ellipse, Freeform
- **Manual ROI** — Enter pixel coordinates directly via numeric dialog
- **Full Image** — One-tap to analyse the entire frame
- **Mask ROI** — Load a binary mask image to exclude irregular regions
- Drag handles for post-draw resize and reposition
- Live pixel coordinate HUD during drawing
- Screen rotation state preservation

### ⚙️ Algorithm Configuration
Setup is a **two-step wizard**: *1 · Load Images* (or video), then *2 · Settings & Run*. Parameters live on a dedicated settings page with **sliders** and **segmented toggles** (no more raw text fields):
- Subset size (px) — slider
- Step size / density (px) — slider
- Strain window (px) — slider
- Strain method: **VSG** (linear plane fit) or **NLVC** (Gaussian derivative kernel) — segmented toggle
- Sub-pixel interpolation kernel: **4×4 Bicubic** or **6×6 Keys** — segmented toggle
- Gaussian pre-blur toggle
- The **Next** and **Compute** buttons stay disabled/faded until images are loaded and the settings page has been reviewed.

### 🚀 Analysis Engine
- Two-pass Hybrid Core: Delaunay Mesh (PATH A) + RGDIC Flood-Fill (PATH B)
- Full 6-DOF shape function: translation, stretch, and shear (\\(u, v, u_x, u_y, v_x, v_y\\))
- Levenberg-Marquardt regularisation (configurable $\alpha$)
- Nelder-Mead Simplex rescue for diverged subsets
- Multi-core parallel execution via OpenMP
- Live progress callback from C++ to Kotlin UI, shown as a **full-screen progress overlay** (ring + %, frame counter, elapsed time)
- Batch analysis: process entire image sequences sequentially

### 📊 Results Viewer
- **5 field maps:** U displacement, V displacement, Exx strain, Eyy strain, Exy shear strain
- Pinch-to-zoom and pan on full-resolution base image with synchronised heatmap overlay
- Interactive **Inspect Probe:** tap any point to read exact field values, coordinates, and ZNSSD quality score
- **Max/Min Marker:** one-tap to place precision reticles at global maximum and minimum field locations
- **Custom Scale Bar:** tap the colour scale to override auto-bounds with manual min/max
- Batch frame navigation (◀ / ▶ arrows with frame counter)
- Colour scale bar with live min/max labels, synchronised to zoom state

### 📤 Export Options
- **Export Image (Current):** saves merged heatmap + base image as PNG to gallery
- **Export PDF Report:** generates a fully formatted A4 PDF report including cover page, algorithm parameters, input image verification card, all 5 field maps with statistics tables, ZNSSD quality heatmap, and complete engine telemetry log
- **Export CSV (Current frame):** raw data table with X, Y, U, V, Exx, Eyy, Exy, ZNSSD per point
- **Export Images (Batch ZIP):** all frames as PNGs in a single ZIP archive
- **Export Master Batch CSV:** single CSV with all frames and all fields

### 🔐 Security & Sign-in
- Email/password sign-in, plus **native "Continue with Google" SSO** (Credential Manager one-tap → Supabase ID-token verification; see [docs/GOOGLE_SSO_SETUP.md](docs/GOOGLE_SSO_SETUP.md))
- Hardware-locked authentication via Android Keystore RSA key pair
- Supabase backend with admin-controlled `APPROVED/PENDING/REVOKED` access tiers (new SSO users are created `PENDING`, same as email registrations)
- Offline-capable: cached session token permits operation without internet after first login
- Self-healing KeyStore: if the Keystore is wiped (factory reset, OS update), the public key is automatically re-registered on next login without requiring re-approval

---

## 4. Step-by-Step User Guide

---

<div style="page-break-inside: avoid;">

### Step 1 — Launch & Authentication

On first launch, inDIC presents the secure login portal. Sign in with your registered **email and password**, or tap **Continue with Google** for native one-tap SSO (requires the Google provider to be configured — see [docs/GOOGLE_SSO_SETUP.md](docs/GOOGLE_SSO_SETUP.md)).

> **Note for new users:** Tap *"Need access? Request an account"* to submit a registration request. Your account will be in `PENDING` status until an administrator approves it (Google sign-ins are also created `PENDING` on first login). You will see the Pending Approval screen (Step 1b) until approval is granted.

<table>
  <tr>
    <td align="center">
      <b>Secure Login</b><br>
      <img src="images/inDIC readme Images/login_screen.jpg" width="250" alt="Login Screen">
    </td>
    <td align="center">
      <b>Account Request</b><br>
      <img src="images/inDIC readme Images/registration_screen.jpg" width="250" alt="Registration Screen">
    </td>
    <td align="center">
      <b>Hardware Lock Status</b><br>
      <img src="images/inDIC readme Images/pending_approval.jpg" width="250" alt="Pending Approval">
    </td>
  </tr>
</table>

</div>

---

<div style="page-break-inside: avoid;">

### Step 2 — Main Analysis Interface

After successful login, you arrive at the **Analysis Studio**, organised as a **two-step wizard** with a step indicator at the top (*1 · Load Images* → *2 · Settings & Run*):

- **Page 1 — Load Images:** a "Load from Video" card at the top, plus the Reference and Deformed image cards with their ROI tools. A gated **Next** button advances to page 2 once both a reference and deformed input are loaded.
- **Page 2 — Settings & Run:** the analysis-parameters settings page (sliders + segmented toggles) and the **Compute Full-Field 2D Strain** button. A **Back** button returns to page 1.

<p align="center">
  <img src="images/inDIC readme Images/main_analysis_interface.jpg" width="300" alt="Main Analysis Interface">
</p>

</div>

---

<div style="page-break-inside: avoid;">

### Step 3 — Loading Your Images

**Reference Image:**
1. Tap **LOAD REFERENCE** to open the system file picker.
2. Select your undeformed (reference state) image.
3. The thumbnail updates immediately. A warning toast appears if a JPEG is selected (lossy compression degrades DIC accuracy — use PNG or TIFF).

**Deformed Image(s):**
1. Tap **LOAD DEFORMED** to open the multi-select file picker.
2. Select one image for a single analysis, or multiple images for batch mode.
3. The label updates to show either the filename (single) or the count (e.g., "12 images selected").

**Or load a video:** Tap **Select Video** on the "Load from Video" card. Choose a clip, then in the sampling dialog pick the **extraction frame rate** and **time segment** (the dialog also shows the video's resolution, fps and length). On **Extract**, a progress overlay counts up while frames are decoded — the first frame of the segment becomes the reference and the rest become the deformed sequence.

**Supported formats:** PNG, TIFF, BMP, DNG, JPEG (with warning); video via any format the device's `MediaMetadataRetriever` can decode (MP4/H.264, etc.)

<p align="center">
  <img src="images/inDIC readme Images/file_picker.jpg" width="300" alt="File Picker">
</p>

</div>

---

<div style="page-break-inside: avoid;">

### Step 4 — Defining Your Region of Interest (ROI)

You have three ways to define the analysis region:

#### Option A: Draw ROI (Recommended)

1. Tap **DRAW ROI**.
2. The ROI Studio opens full-screen with your reference image displayed.
3. Select a shape from the toolbar: **Rectangle**, **Square**, **Circle**, **Ellipse**, or **Freeform**.
4. Drag on the image to draw your ROI. White corner handles appear for resizing. Drag from inside the box to reposition.
5. The HUD bar at the top displays live pixel dimensions and position: `ROI: 1200 x 800 px | Pos: (312, 240)`.
6. Tap **SAVE ROI** to confirm and return.

> **Tip:** Always leave at least half a subset-width of clearance from the image boundary. The engine will skip subsets that would overlap the image edge.

<table>
  <tr>
    <td align="center">
      <b>Standard Rectangular ROI</b><br>
      <img src="images/inDIC readme Images/roi_rectangle.png" width="350" alt="Rectangular ROI">
    </td>
    <td align="center">
      <b>Freeform Boundary Tracing</b><br>
      <img src="images/inDIC readme Images/roi_freeform.png" width="350" alt="Freeform ROI">
    </td>
  </tr>
</table>

</div>

<div style="page-break-inside: avoid;">

#### Option B: Manual ROI Entry

1. Tap **MANUAL ROI**.
2. Enter the origin coordinates (X, Y) and dimensions (Width, Height) in pixels directly.
3. Tap **APPLY** to confirm.

<p align="center">
  <img src="images/inDIC readme Images/manual_roi_dialog.jpg" width="300" alt="Manual ROI Dialog">
</p>

#### Option C: Full Image
Tap **FULL IMAGE** to use the entire image frame as the analysis region. The status label updates to "✅ Using Full Image".

</div>

---

<div style="page-break-inside: avoid;">

### Step 5 — Configuring Analysis Parameters

On **page 2 (Settings & Run)**, the parameters are a settings page with **sliders** (each showing its live value) and **segmented toggles**:

| Parameter | Control | Description | Typical Range |
|---|---|---|---|
| **Subset Size** | slider | Side length of the correlation window (pixels). Larger = more robust but lower spatial resolution. | 15–101 px |
| **Step Size** | slider | Spacing between adjacent grid points (pixels). Smaller = higher point density but longer compute time. | 1–30 px |
| **Strain Window** | slider | Neighbourhood size for strain calculation (pixels). Controls spatial averaging of the strain field. | 5–51 px |

**Strain Method (segmented toggle):**
- **VSG** — Virtual Strain Gauge: linear plane fit. Faster, standard. Recommended for most use cases.
- **NLVC** — Non-Local Virtual Compressor: Gaussian derivative kernel. Smoother, better noise floor.

**Sub-pixel Interpolation Kernel (segmented toggle):**
- **4×4 Bicubic** — Keys cubic convolution, fast (default).
- **6×6 Keys** — 4th-order Keys kernel for DICe parity.

**Gaussian Blur Toggle:**
Enable the pre-filter switch if your images have sensor noise or compression artifacts. Disable for high-quality optical images to preserve sharp speckle boundaries.

</div>

---

<div style="page-break-inside: avoid;">

### Step 6 — Running the Analysis

1. On page 2, confirm inputs are loaded (the **Compute** button is enabled, not faded).
2. Tap **Compute Full-Field 2D Strain**.
3. A **full-screen progress overlay** appears — a circular progress ring with live %, the current frame counter, and an elapsed timer. Progress updates live from the C++ engine.
4. For batch mode, the overlay shows "Processing frame 3 of 12" as each deformed image is processed sequentially.
5. On completion, the results viewer opens automatically (and **VIEW RESULTS** stays available to reopen it).

> **During processing:** The back button is intercepted. Pressing it shows a warning dialog rather than killing the thread mid-computation, preventing memory corruption.

<p align="center">
  <img src="images/inDIC readme Images/analysis_running.jpg" width="300" alt="Analysis Running Progress">
</p>

</div>

---

<div style="page-break-inside: avoid;">

### Step 7 — The Results Viewer

After tapping **VIEW RESULTS**, the Results Viewer opens. This is the primary analysis output screen.

<p align="center">
  <img src="images/inDIC readme Images/results_viewer_main.jpg" width="300" alt="Full Results Viewer Interface">
</p>

</div>

---

<div style="page-break-inside: avoid;">

#### Feature A: Switching Field Maps

Use the **field selector spinner** at the top to switch between the five available output fields:

| Field | Description | Units |
|---|---|---|
| **U** | Horizontal (X-axis) displacement | pixels |
| **V** | Vertical (Y-axis) displacement | pixels |
| **Exx** | Normal strain along X axis | dimensionless (shown as με in reports) |
| **Eyy** | Normal strain along Y axis | dimensionless |
| **Exy** | Shear strain | dimensionless |

The heatmap re-renders immediately on selection. The colour scale bar updates to the new field's bounds.

<p align="center">
  <img src="images/inDIC readme Images/results_exx_strain.jpg" width="300" alt="Exx Strain Heatmap">
</p>

</div>

---

<div style="page-break-inside: avoid;">

#### Feature B: Pinch-to-Zoom and Pan

The base image and heatmap overlay are fully zoomable and pannable via standard pinch and drag gestures. The heatmap overlay matrix is synchronised to the base image zoom matrix in real time — both layers remain perfectly aligned at all zoom levels.

#### Feature C: Inspect Probe

1. Tap the **INSPECT** toggle button to activate probe mode.
2. Tap or drag your finger on any point of the image.
3. The engine finds the nearest solved grid point to your finger position.
4. An **Inspector HUD card** appears showing:
   - Image coordinates (X, Y) in pixels
   - Current field value (e.g., `U = -0.3142 px`)
   - ZNSSD correlation score (e.g., `ZNSSD = 0.0042`)
   - A green crosshair reticle marks the exact measurement point on the image

<p align="center">
  <img src="images/inDIC readme Images/inspect_probe.jpg" width="300" alt="Inspect Probe HUD">
</p>

</div>

---

<div style="page-break-inside: avoid;">

#### Feature D: Max/Min Global Markers

1. Tap the **MAX/MIN** toggle button.
2. The engine scans the entire solved field for the global maximum and minimum values.
3. Two precision reticles are placed:
   - **Red reticle** — Global maximum location
   - **Cyan reticle** — Global minimum location
4. A **Max/Min HUD card** shows both values with their pixel coordinates.

<p align="center">
  <img src="images/inDIC readme Images/max_min_markers.jpg" width="300" alt="Max and Min Markers">
</p>

</div>

---

<div style="page-break-inside: avoid;">

#### Feature E: Coordinate Input (Go-To Point)

1. Tap the **XY** button (coordinate input).
2. Enter the image pixel coordinates of a specific point.
3. The Inspect probe jumps directly to the nearest solved grid point at those coordinates and displays its data.

#### Feature F: Custom Scale Bar

1. Tap anywhere on the **colour scale bar** (right side of screen).
2. A dialog appears with current Min/Max bounds pre-filled.
3. Enter custom bounds to lock the colour scale (useful for comparing multiple frames at the same scale).
4. Tap **APPLY**. The heatmap immediately re-renders with the new bounds.
5. To revert to auto-scaling, clear the fields and apply.

<p align="center">
  <img src="images/inDIC readme Images/custom_scale_dialog.jpg" width="300" alt="Custom Scale Bounds Dialog">
</p>

</div>

---

<div style="page-break-inside: avoid;">

#### Feature G: Batch Frame Navigation

When a batch of multiple deformed images was processed:
- Use the **◀** and **▶** navigation arrows to step through frames.
- The **frame counter** (e.g., "Frame 3 / 12") updates with each step.
- All viewer features (Inspect, Max/Min, export) operate on the currently displayed frame.

<p align="center">
  <img src="images/inDIC readme Images/batch_navigation.jpg" width="300" alt="Batch Navigation Controls">
</p>

</div>

---

<div style="page-break-inside: avoid;">

### Step 8 — Exporting Results

Use the **Export spinner** and **Execute (▶) button** at the bottom of the Results Viewer to export data:

#### PDF Report
Select **"Export PDF Report"** and tap Execute.

The report generates in the background and is saved to your device's Documents folder. It contains:

- **Cover Page:** Session ID, specimen name, date, algorithm parameters, ROI dimensions, and an input verification card with reference and deformed image thumbnails side by side
- **Field Pages (×5):** Each of U, V, Exx, Eyy, Exy gets a dedicated section with a statistics table (Max, Min, Mean, Std Dev, coordinates) and a full-page heatmap render
- **Diagnostic Page:** ZNSSD quality heatmap
- **Engine Telemetry Page:** Complete profiling breakdown (AKAZE/RANSAC time, Hessian pre-pass, Delaunay mesh, Path A/B point counts, Simplex rescue statistics, total wall time, average ICGN iterations)

<table>
  <tr>
    <td align="center">
      <b>Cover & Specimen Data</b><br>
      <img src="images/inDIC readme Images/pdf_cover.jpg" width="250" alt="PDF Cover Page">
    </td>
    <td align="center">
      <b>Strain Fields & Statistics</b><br>
      <img src="images/inDIC readme Images/pdf_field_page.jpg" width="250" alt="PDF Field Page">
    </td>
    <td align="center">
      <b>Engine Telemetry Log</b><br>
      <img src="images/inDIC readme Images/pdf_telemetry.jpg" width="250" alt="PDF Telemetry Page">
    </td>
  </tr>
</table>

</div>

#### CSV Export
Select **"Export CSV (Current)"** to export a tab-separated data file with columns:

```text
X, Y, U, V, Exx, Eyy, Exy, ZNSSD
````

One row per solved grid point. Invalid/failed points are omitted.

#### Batch Exports (Batch Mode Only)

  - **Export Images (Batch ZIP):** All frame heatmaps packaged as a ZIP archive in Downloads
  - **Export Master Batch CSV:** All frames concatenated in a single CSV with a `Frame` index column

-----

## 5\. Installation & Build Instructions

### Prerequisites

| Tool | Version | Notes |
|---|---|---|
| Android Studio | Ladybug (2024.2) or newer | Required for CMake integration |
| Android NDK | r27+ (tested with 28.2) | r27+ builds 16 KB-page-aligned libs by default |
| CMake | 3.22.1 | Bundled with NDK, or install via SDK Manager |
| Min Android API | 24 (Android 7.0) | `minSdk = 24`; `compileSdk`/`targetSdk = 36` |
| Target ABIs | all (`arm64-v8a`, `armeabi-v7a`, `x86`, `x86_64`) | No `abiFilters` — every ABI is built; APK splits + a universal APK are produced |
| OpenCV | 4.12.0 | **Git submodule** at `app/src/main/cpp/third_party/opencv`, **built from source** (curated modules: core, imgproc, imgcodecs, features2d, calib3d, flann) |
| Eigen | 3.4.0 | **Git submodule** at `app/src/main/cpp/third_party/eigen` |

-----

### Step 1 — Clone the Repository (with submodules)

Clone the repository and initialise submodules:

```bash
git clone --recurse-submodules https://github.com/<org>/IndicVisionDIC.git
# or, if already cloned:
git submodule update --init --recursive
```

Both native dependencies are **git submodules pinned to release tags**:

- **Eigen** → `app/src/main/cpp/third_party/eigen` @ **3.4.0** (header-only)
- **OpenCV** → `app/src/main/cpp/third_party/opencv` @ **4.12.0**, **compiled from source** as part of the native build (only the modules the engine uses: core, imgproc, imgcodecs, features2d, calib3d, flann; its bundled 3rd-party image codecs are built in-tree, so no system libraries are required).

> **Build-time note:** Because OpenCV is built from source, the **first** native build compiles OpenCV once per ABI and is slower than a prebuilt SDK (a few minutes per ABI with the curated module list). Subsequent builds are incremental (ninja caches the OpenCV objects). No Python is required — OpenCV's Python detection is disabled in `CMakeLists.txt` since no bindings are built.

-----

### Step 2 — Configure Supabase Credentials

The app's authentication backend uses Supabase. Credentials are injected via `BuildConfig` from `local.properties` (never committed to version control).

Add the following to `local.properties` in the project root:

```properties
SUPABASE_URL=https://your-project-id.supabase.co
SUPABASE_ANON_KEY=your-supabase-anon-key-here
# Optional — only needed for "Continue with Google" SSO (see docs/GOOGLE_SSO_SETUP.md)
GOOGLE_WEB_CLIENT_ID=1234567890-abcdefg.apps.googleusercontent.com
```

These are read in `app/build.gradle.kts` and exposed as `BuildConfig.SUPABASE_URL`, `BuildConfig.SUPABASE_ANON_KEY`, and `BuildConfig.GOOGLE_WEB_CLIENT_ID`. `SupabaseManager.kt` already references the Supabase constants; the Google client ID is used by `ui/GoogleSignInHelper.kt`.

> **For contributors building a standalone version:** You can substitute a self-hosted Supabase instance or replace `AuthRepository.kt` with a simple stub that returns `Result.success("APPROVED")` to bypass authentication during development. Note that debug builds may also skip straight to the analysis screen (a `BuildConfig.DEBUG` gate in `SplashActivity`) for fast iteration.

-----

### Step 3 — (Optional) Configure Google SSO

To enable **Continue with Google**, complete the Google Cloud + Supabase setup in [docs/GOOGLE_SSO_SETUP.md](docs/GOOGLE_SSO_SETUP.md) and add `GOOGLE_WEB_CLIENT_ID` to `local.properties`. Until then, the button shows a "not set up yet" hint and the rest of the app works normally.

-----

### Step 4 — Build & Run

1.  Open the project root in Android Studio.
2.  Let Gradle sync complete (this will download Kotlin/Java dependencies via Maven).
3.  Connect any Android device (API 24+) or create an AVD — **any ABI works** (arm64-v8a, x86_64 emulators included). For Google SSO testing, use a **Google APIs / Play Store** emulator image.
4.  Select the `debug` build variant.
5.  Click **Run ▶**.

> **Build time note:** The first CMake build compiles the full C++ engine across all ABIs (including OpenCV + Eigen headers). Expect several minutes on first build. Subsequent incremental builds are \<30 seconds. The build produces per-ABI split APKs plus a universal APK under `app/build/outputs/apk/`.

-----

### Build Variants

| Variant | Description |
|---|---|
| `debug` | Full logcat output, LM damping diagnostics, debug image exports to `/sdcard/inDIC_debug/` |
| `release` | ProGuard enabled, debug logging stripped, production Supabase credentials |

-----

### Common Build Issues

  - **OpenCV headers not found / submodule empty** — run `git submodule update --init --recursive`. OpenCV is built from source at `app/src/main/cpp/third_party/opencv` via `add_subdirectory` in `CMakeLists.txt`.
  - **First native build is slow** — expected: OpenCV compiles from source once per ABI. Subsequent builds are cached by ninja. (No Python is needed; `OPENCV_PYTHON_SKIP_DETECTION` is set.)
  - **`ANDROID_NDK not set`** — Open SDK Manager → SDK Tools → Install NDK (Side by side, r27+). Gradle picks it up automatically; you can pin it via `ndkVersion` in `app/build.gradle.kts`.
  - **`arm_neon.h not found`** — Should not occur: the engine no longer includes `arm_neon.h` unconditionally. SIMD goes through OpenCV universal intrinsics (`core/SimdKernels.h`), which compile for every ABI. If you reintroduce raw NEON, guard the include with `#if defined(__aarch64__)`.
  - **App crashes only on the emulator (native)** — Ensure you're on the current build: all ABIs are compiled, so x86/x86_64 emulators run the native engine natively. (Historically this was an `arm64-v8a`-only `abiFilter` — now removed.)
  - **"not 16 KB compatible" warning** — Build with NDK r27+ (produces 16 KB-aligned libraries; the CMake link flags also force `max-page-size=16384`).
  - **`SIGSEGV on first OpenMP call`** — This means the JNI call is being made from a new OS thread each time. Verify `AnalysisViewModel.nativeExecutor` is being used for all `IndicVisionNativeLib` calls in `StaticAnalysisActivity`.

-----

## 6\. Performance & Validation

### Validation 1 — Sub-Pixel Accuracy vs. Sandia DICe (Sample 14)

**Test setup:** DIC Challenge 2D 1.0 Sample 14, Level 5 sinusoidal displacement field. Commanded amplitude: 0.1 px. Subset size: 41, Step: 5.

| Solver | RMSE (px) ↓ | Max Bias (px) ↓ | Std Dev (px) ↓ | Spatial Res. Cutoff (px) ↑ |
|---|---|---|---|---|
| **DICe (Sandia)** | 0.00791 | 0.02769 | 0.00792 | 964.4 |
| **inDIC (ARM64)** | **0.00778** | **0.02691** | **0.00779** | **1078.3** |

> inDIC matches and marginally outperforms Sandia National Labs' desktop software on every metric, including a **12% improvement in spatial resolution cutoff** — running natively on a smartphone CPU.

-----

### Validation 2 — Extreme Affine Distortion (25° Rotation)

**Test setup:** Synthetic speckle image, 25° rigid body rotation + 0.31 px translation. Subset size: 41, Step: 5. All four pipelines tested on identical hardware.

| Pipeline | Valid Points | RMSE U (px) ↓ | RMSE Exx (με) ↓ | Mean ZNSSD ↓ |
|---|---|---|---|---|
| **inDIC Hybrid (P2)** | **78,107** | **0.00284** | 220.3 | **1.63e-05** |
| **inDIC Global RGDIC (P5)** | 78,106 | 0.00284 | 220.7 | 1.63e-05 |
| DICe KD Tree (P6) | 71,420 | 0.590 | 96,581 | 5.68e-05 |
| DICe DD RGDIC (P7) | 75,783 | 0.331 | 212.1 | 2.02e-05 |

> **Direct ports of PC DIC algorithms catastrophically fail extreme rotations on mobile ARM64.** DICe's KD-Tree pipeline diverges with a 208× RMSE increase (0.590 px vs 0.003 px). inDIC's Hybrid Core maintains sub-pixel accuracy and 100% convergence across all 78,107 points.

-----

### Engine Throughput

| Metric | Value |
|---|---|
| Grid points solved | 78,107 |
| Total wall time | 4.6 s |
| Average ICGN iterations | 4.7 per point |
| Hessian pre-pass | 0.8 s (one-time, OMP parallel) |
| Path A (Delaunay) | 1.4 s |
| Path B (RGDIC) | 1.6 s |
| Strain calculation | 0.5 s |
| Peak throughput | \~17,000 pts/s |

-----

<div style="page-break-inside: avoid;"\>

## 7\. Architecture Overview

```text
┌─────────────────────────────────────────────────────────────────┐
│                        KOTLIN LAYER                             │
│                                                                 │
│  SplashActivity → AuthActivity → StaticAnalysisActivity         │
│                                        │                        │
│                              RoiDrawActivity                    │
│                              (StudioOverlayView)                │
│                                        │                        │
│                              AnalysisViewModel                  │
│                              (nativeExecutor thread)            │
│                                        │                        │
│                              ResultViewerActivity               │
│                              (TouchImageView +                  │
│                               VisualizationEngine +             │
│                               PdfReportGenerator)               │
└─────────────────────────────┬───────────────────────────────────┘
                              │ JNI Bridge (IndicVisionNativeLib.kt)
┌─────────────────────────────▼───────────────────────────────────┐
│                        C++ NATIVE LAYER                         │
│                                                                 │
│  IndicVisionJNI.cpp                                             │
│  ├── Phase 0: Image prep (7-tap Gaussian, 4th-order gradients)  │
│  ├── Phase 1: AKAZE + RANSAC (OpenCV)                           │
│  ├── Phase 2: Delaunay mesh + 6-DOF affine warp per triangle    │
│  ├── Phase 3: OMP parallel Hessian pre-pass                     │
│  ├── Phase 4: PATH A — OMP parallel ICGN over mesh points       │
│  ├── Phase 5: PATH B — Priority-queue RGDIC flood-fill          │
│  └── Phase 6: VSG or NLVC Green-Lagrange strain                 │
│                                                                 │
│  OptimizationEngine.cpp   SubsetPrecomputer.cpp                 │
│  (ICGN + Simplex solver)  (Hessian pool + fast precompute)      │
│                                                                 │
│  ImageProcessor.cpp       StrainCalculator.cpp                  │
│  (Keys bicubic interp.)   (VSG LDLT + NLVC Gaussian kernel)     │
│                                                                 │
│  SimdKernels.h — portable SIMD hot loops (NEON on ARM, SSE on   │
│  x86) via OpenCV universal intrinsics; built for all ABIs       │
└─────────────────────────────────────────────────────────────────┘
                              │
┌─────────────────────────────▼───────────────────────────────────┐
│                     BACKEND (Supabase)                          │
│                                                                 │
│  auth_profiles table: user_id, device_fingerprint,              │
│  hardware_public_key, access_status (APPROVED/PENDING/REVOKED)  │
│                                                                 │
│  analysis_sessions table: session telemetry logging             │
└─────────────────────────────────────────────────────────────────┘
```

</div>

-----

## 8\. Security Model

inDIC implements a **two-factor hardware lock** to prevent licence sharing and ensure that each approved account can only operate on the specific physical device it was registered from.

<div style="page-break-inside: avoid;"\>

### Authentication Flow

```text
Device Boot
    │
    ▼
SplashActivity — checks local Supabase session token
    │
    ├── No token → AuthActivity (Email/password · Register · Continue with Google)
    │                   └── Google SSO: Credential Manager → ID token →
    │                       Supabase signInWith(IDToken) → create PENDING profile
    │                       on first login → back to SplashActivity routing
    │
    └── Token found → query auth_profiles
            │
            ├── access_status == "PENDING" → PendingApprovalActivity
            ├── access_status == "REVOKED" → sign out + AuthActivity
            ├── device_fingerprint mismatch → sign out + AuthActivity ("UNAUTHORIZED HARDWARE")
            ├── HTTP error (no internet) → "OFFLINE_CACHE_APPROVED" → StaticAnalysisActivity
            └── access_status == "APPROVED" + fingerprint match → StaticAnalysisActivity
```

</div>

### Hardware Lock Components

**`DeviceKeyManager.kt`** manages two independent hardware-bound identifiers:

1.  **`ANDROID_ID`** — A permanent 64-bit hardware identifier (`Settings.Secure.ANDROID_ID`) that survives app uninstalls and data clears, but changes on factory reset. This is stored as `device_fingerprint` in Supabase and checked on every login.
2.  **RSA Key Pair (Android Keystore)** — An un-extractable 2048-bit RSA key pair generated inside the hardware-backed Android Keystore TEE. The public key is stored in Supabase as `hardware_public_key`. On login, if the public key in the vault differs from the device's current key (indicating a Keystore wipe after OS update), the key is silently self-healed via a database update — without requiring re-approval.

### Offline Mode

If the Supabase server is unreachable (no internet), `AuthRepository.checkUserAccessStatus()` catches `HttpRequestException` and returns the special status `"OFFLINE_CACHE_APPROVED"`. The app proceeds to the analysis engine using the locally cached JWT token, allowing field engineers to operate without connectivity after their first successful online login.

-----

## 9\. Roadmap

  - [ ] **Adaptive Subset Sizing** — Automatically vary subset size based on local speckle density (feature count from AKAZE within neighbourhood radius)
  - [ ] **Dynamic Iteration Budget** — Throttle ICGN `max_iter` based on per-device ARM64 benchmark score, preventing CPU timeout on lower-end devices
  - [ ] **Real-World Specimen Validation** — Transition from synthetic DIC Challenge datasets to physically deformed test coupons (tensile specimens, beam bending)
  - [ ] **3D Stereo DIC** — Dual-phone stereo acquisition mode using Wi-Fi-Direct synchronisation for out-of-plane displacement measurement
  - [ ] **Project Persistence** — Save and reload complete analysis sessions (images + results + parameters) to device storage
  - [x] **Video input** — Extract reference + deformed frames directly from a video clip with selectable frame rate and time segment
  - [x] **UI/UX overhaul** — Deep-sky-blue design system, two-step setup wizard, sliders/segmented toggles, full-screen progress overlay, edge-to-edge insets
  - [x] **Cross-architecture engine** — Portable SIMD (NEON/SSE) and all-ABI builds so the app runs on emulators and non-ARM64 devices
  - [x] **Google SSO** — Native one-tap sign-in via Credential Manager + Supabase
  - [ ] **Results history gallery, annotation layer, shareable report links**
  - [ ] **Pure RGDIC Path** — An optional pipeline for ROIs with no AKAZE features (e.g. uniform-texture specimens with small deformations)

-----

## 10\. Acknowledgements

**Academic Supervision:**
Dr. Sankara J. Subramanian, IndicVision

**Reference Implementations:**

  - [DICe](https://github.com/dicengine/dice) — Sandia National Laboratories (Baker & Bruck, 2014) — algorithmic reference for ICGN formulation, Hessian structure, and VSG strain
  - [Ncorr](http://www.ncorr.com) — Blaber et al. (2015) — reference for IC-GN architecture comparison
  - [ALDIC](https://github.com/FranckLab/ALDIC) — Yang & Franck (2019) — reference for Augmented Lagrangian global DIC

**Benchmarking Dataset:**
[DIC Challenge 2D 1.0](https://sem.org/dicchallenge) — Society for Experimental Mechanics (Reu et al., 2018)

**Libraries Used:**

  - [Eigen 3.4](https://eigen.tuxfamily.org) — Linear algebra (MIT License)
  - [OpenCV 4.x](https://opencv.org) — AKAZE, Delaunay, image I/O, and universal intrinsics for portable SIMD (Apache 2.0)
  - [Supabase](https://supabase.com) — Auth + database backend (Apache 2.0)
  - [AndroidX Credential Manager + Google Identity](https://developer.android.com/training/sign-in/credential-manager) — native Google SSO
  - [Kotlin Coroutines](https://github.com/Kotlin/kotlinx.coroutines) — Async execution
  - Portable SIMD (NEON on ARM, SSE on x86) via OpenCV universal intrinsics

-----


**inDIC** — Built at IndicVision

*Bringing precision metrology to the field, one phone at a time.*
