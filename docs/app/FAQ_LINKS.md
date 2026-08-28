# FAQ links from the app

Actionable errors and warning chips that open the public FAQ
(`https://semperdic.github.io/website/manual/faq/`). Every hop asks first
(leave-the-app confirm via `FaqRedirect.confirm`).

**Canonical text:** [FAQ.md](FAQ.md) in this repo — copy or render into
[semperdic/website](https://github.com/semperdic/website) on release. Anchor IDs
in that file must stay in sync with `url_faq_*` in `strings.xml`.

---

## Full FAQ index

| Anchor | Topic | App resource |
|--------|-------|--------------|
| `#jpeg-warning` | Lossy reference / frames | `url_faq_jpeg` |
| `#speckle-contrast` | Low SSSIG / speckle fail | `url_faq_speckle` |
| `#noise-floor` | Measurement floor dialog & burst | `url_faq_noise_floor` |
| `#lighting-and-accuracy` | Lighting vs strain (lab study) | *(same page; link from FAQ body)* |
| `#strain-field-stats` | Mean / max / robust σ | *(same page; link from FAQ body)* |
| `#imaging-pipeline` | ISP refusals & smoothing | `url_faq_imaging_pipeline` |
| `#frame-size-mismatch` | Frame size chip | `url_faq_frame_size` |
| `#roi-too-small` | ROI vs subset | `url_faq_roi_too_small` |
| `#sweep-subset-range` | Sweep subset range | `url_faq_sweep_subset_range` |
| `#sweep-empty-plan` | Empty sweep plan | `url_faq_sweep_empty_plan` |
| `#engine-features` | Decorrelation | `url_faq_engine_features` |
| `#engine-roi` | Empty ROI | `url_faq_engine_roi` |
| `#engine-init` | Decode / init | `url_faq_engine_init` |
| `#engine-convergence` | Low convergence | `url_faq_engine_convergence` |
| `#engine-vsg` | Strain window / lattice | `url_faq_engine_vsg` |
| `#import-reference` | Reference import | `url_faq_import_reference` |
| `#import-deformed` | Deformed import | `url_faq_import_deformed` |
| `#video-read` | Video metadata | `url_faq_video_read` |
| `#video-extract` | Video extract | `url_faq_video_extract` |
| `#no-batch-data` | Missing `.dat` | `url_faq_no_batch_data` |
| `#viewer-oom` | Viewer OOM | `url_faq_viewer_oom` |
| `#custom-scale` | Colour scale | `url_faq_custom_scale` |

---

## Trigger map (Why? / ⓘ)

| From | Trigger | String / surface | FAQ URL resource | Anchor |
|------|---------|------------------|------------------|--------|
| Wizard step 1 | Lossy-format accuracy chip | `lossy_format_warning_fmt` + chip FAQ | `url_faq_jpeg` | `#jpeg-warning` |
| Wizard step 1 | Low speckle / SSSIG chip | `texture_low_fmt` + chip FAQ | `url_faq_speckle` | `#speckle-contrast` |
| Wizard step 2 | Frame-size mismatch chip | `frames_size_mismatch_fmt` + chip FAQ | `url_faq_frame_size` | `#frame-size-mismatch` |
| Wizard step 2 | ROI smaller than subset | `roi_too_small` snackbar **Why?** | `url_faq_roi_too_small` | `#roi-too-small` |
| Wizard sweep | Subset range above ROI | sweep plan chip | `url_faq_sweep_subset_range` | `#sweep-subset-range` |
| Wizard sweep | Empty plan | sweep plan chip | `url_faq_sweep_empty_plan` | `#sweep-empty-plan` |
| Wizard (run) | Engine failure dialog | `EngineFailure.reasonRes` + **Why?**; ⓘ beside `tvStaticResult` | `url_faq_engine_*` | `#engine-*` |
| Wizard import | Reference decode / load failed | snackbar **Why?** | `url_faq_import_reference` | `#import-reference` |
| Wizard import | Deformed batch load failed | snackbar **Why?** | `url_faq_import_deformed` | `#import-deformed` |
| Wizard video | Meta read failed | snackbar **Why?** | `url_faq_video_read` | `#video-read` |
| Wizard video | Extract too few frames | snackbar **Why?** | `url_faq_video_extract` | `#video-extract` |
| Result viewer | No `.dat` batch | snackbar **Why?** | `url_faq_no_batch_data` | `#no-batch-data` |
| Result viewer | OOM loading frame | snackbar **Why?** | `url_faq_viewer_oom` | `#viewer-oom` |
| Result viewer | Custom scale min ≥ max | snackbar **Why?** | `url_faq_custom_scale` | `#custom-scale` |
| Result viewer | Strain field floor caption | `capture_noise_floor_readout` / `CaptureNoiseFloor.warning()` | `url_faq_noise_floor` | `#noise-floor` |
| Capture test shot | Floor **pass** dialog | Large value + `capture_noise_floor_body`; **ⓘ** (does not dismiss) | `url_faq_noise_floor` | `#noise-floor` |
| Capture test shot | Floor **fail** / drift / unsettled | `capture_noise_erroneous_*` / unsettled / drift; **Why?** (does not dismiss) | `url_faq_noise_floor` | `#noise-floor` |
| Capture test shot | Speckle-fail dialog | **Why?** | `url_faq_speckle` | `#speckle-contrast` |
| Capture test shot | HAL refused settings | `capture_isp_warn_more` snackbar | `url_faq_imaging_pipeline` | `#imaging-pipeline` |
| Capture test shot | Burst frames smoothed | `capture_denoise_warn` snackbar | `url_faq_imaging_pipeline` | `#imaging-pipeline` |
| Lattice | Hollow node tap | short reason + **Why?** | `EngineFailure.faqUrlRes` | `#engine-*` |
| Lattice | All combinations failed | tap summary | `url_faq_engine_vsg` | `#engine-vsg` |

---

## Related in-app copy (2026-08-28)

Measurement floor dialog strings (`capture_noise_floor_*`):

- **Pass:** label `capture_noise_floor_label`, body `capture_noise_floor_body`, button `capture_noise_continue`
- **Fail:** title `capture_noise_erroneous_title`, body `capture_noise_erroneous_body` (floor value in layout, not repeated in body)
- **Viewer:** `capture_noise_floor_readout` under strain colour bar

Deep dives in repo (not linked from app): `.hitl-pull/NOISE_FLOOR_GUIDE.md`,
`.hitl-pull/ROI_METHODOLOGY.md`, lighting sweep reports under
`.hitl-pull/pixel-noisetest-report/`.
