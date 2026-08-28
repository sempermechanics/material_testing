# FAQ links from the app

Actionable errors and warning chips that can open the public FAQ
(`https://semperdic.github.io/website/manual/faq/`). Every hop asks first
(leave-the-app confirm via `FaqRedirect.confirm`). Success toasts, coach marks,
and quota / sign-in / delete confirms do **not** link here.

Anchors below are **guessed** against the hosted site; this repo does not own
that site. If a heading moves, update the matching `url_faq_*` string.

| From | Trigger | String / surface | FAQ URL resource | Guessed heading / anchor |
|------|---------|------------------|------------------|--------------------------|
| Wizard step 1 | Lossy-format accuracy chip (any non-lossless frame or reference, not only JPEG) | `lossy_format_warning_fmt` + chip FAQ | `url_faq_jpeg` | `#jpeg-warning` |
| Wizard step 1 | Low speckle / SSSIG chip | `texture_low_fmt` + chip FAQ | `url_faq_speckle` | `#speckle-contrast` |
| Wizard step 2 | Frame-size mismatch chip | `frames_size_mismatch_fmt` + chip FAQ | `url_faq_frame_size` | `#frame-size-mismatch` |
| Wizard step 2 | ROI smaller than subset | `roi_too_small` snackbar **Why?** | `url_faq_roi_too_small` | `#roi-too-small` |
| Wizard sweep | Subset range above ROI | sweep plan chip | `url_faq_sweep_subset_range` | `#sweep-subset-range` |
| Wizard sweep | Empty plan (no combinations) | sweep plan chip | `url_faq_sweep_empty_plan` | `#sweep-empty-plan` |
| Wizard (run) | Engine failure dialog | `EngineFailure.reasonRes` + **Why?**; after dismiss, ⓘ beside `tvStaticResult` | `url_faq_engine_features` / `_roi` / `_init` / `_convergence` / `_vsg` | `#engine-features` … `#engine-vsg` |
| Wizard import | Reference decode / load failed | `failed_load_reference` / `failed_decode_raw` snackbar **Why?** | `url_faq_import_reference` | `#import-reference` |
| Wizard import | Deformed batch load failed | `error_loading_images` snackbar **Why?** | `url_faq_import_deformed` | `#import-deformed` |
| Wizard video | Meta read failed | `video_read_failed` snackbar **Why?** | `url_faq_video_read` | `#video-read` |
| Wizard video | Extract produced too few frames / error | `video_extract_insufficient` / `video_read_error` snackbar **Why?** | `url_faq_video_extract` | `#video-extract` |
| Result viewer | No `.dat` batch on open | `no_batch_data` snackbar **Why?** | `url_faq_no_batch_data` | `#no-batch-data` |
| Result viewer | OOM while loading a frame | `viewer_frame_oom` snackbar **Why?** | `url_faq_viewer_oom` | `#viewer-oom` |
| Result viewer | Custom scale min ≥ max | `invalid_scale_inputs` snackbar **Why?** | `url_faq_custom_scale` | `#custom-scale` |
| Capture test shot | Noise-floor pass dialog (**ⓘ** in title) | `capture_noise_floor_*` | `url_faq_noise_floor` | `#noise-floor` |
| Capture test shot | Noise-floor fail / drift / unsettled dialog (**Why?** neutral button) | `capture_noise_*_title` / `_body` | `url_faq_noise_floor` | `#noise-floor` |
| Capture test shot | Settings the HAL refused, collapsed to one line | `capture_isp_warn_more` snackbar | `url_faq_imaging_pipeline` | `#imaging-pipeline` |
| Capture test shot | Burst frames came back smoothed (neighbour correlation > 0.5) | `capture_denoise_warn` snackbar | `url_faq_imaging_pipeline` | `#imaging-pipeline` |
| Lattice | Hollow node tap | short reason dialog **Why?** | same `url_faq_engine_*` as the node code | `#engine-*` |
| Lattice | All combinations failed (summary line) | tap `vsg_lattice_all_failed` | `url_faq_engine_vsg` | `#engine-vsg` |

Shipped chip links (#91): JPEG, speckle, frame size, ROI, sweep subset range,
sweep empty plan. This map adds the engine-failure and import/viewer/lattice
rows above.
