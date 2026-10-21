# Architecture decision records

One file per decision that is expensive to rediscover and easy to undo by
accident. Format: Status / Context / Decision / Options / Trade-offs /
Consequences / Action items. A superseded record stays; its status names the
record that replaced it.

| ADR | Title | Status | Register |
|-----|-------|--------|----------|
| [001](ADR-001-firestore-repo-package.md) | Split `firestore_repo.py` into a package behind a facade | Accepted, built; facade superseded by 021 | TD-53, TD-58 |
| [002](ADR-002-cloudapi-seam.md) | `CloudApi` interface seam for `SemperApi` | Accepted, built | TD-25 |
| [003](ADR-003-viewerargs-read-side.md) | `ViewerArgs.from` read side with a `SessionRecord` fallback | Accepted, built | TD-3, TD-61 |
| [004](ADR-004-runspec.md) | Immutable `RunSpec` built once at Compute | Accepted, built | FI-6, TD-61 |
| [005](ADR-005-wizard-process-death.md) | The wizard survives process death through a draft | Accepted, built | TD-26 |
| [006](ADR-006-gateway-deploy-job.md) | CI deploys the API Gateway after the Cloud Run promote | Accepted, built (not yet dispatched) | TD-27 |
| [007](ADR-007-licence-lifecycle.md) | Licence lifecycle: one per person, replace by revoke, delete into a 30-day hold | Accepted, built (TTL policies owed); superseded in part by 024 (no key typed in the app) | — |
| [008](ADR-008-startup-gates-phone-state.md) | Real-device startup gates check the phone's state; a trip is settled A/B | Accepted, built (references owed) | TD-135 |
| [009](ADR-009-material-testing-app-identity.md) | Material Testing is its own Android app on Semper's backend | Accepted, built (App Check owed) | TD-133 |
| [010](ADR-010-device-binding-per-app.md) | Device binding per app: one phone per app, not per account | Accepted, built | TD-137, TD-138 |
| [011](ADR-011-viewer-deformed-frame.md) | The viewer draws each frame on its own photo, at the displaced positions | Accepted, built; exports follow 2026-10-13 (semperdic-app) | TD-139 (closed) |
| [012](ADR-012-tensile-strain-virtual-extensometer.md) | Tensile strain is the virtual extensometer's ΔL / L₀ | Accepted, built | TD-144 (fixed), TD-147 (fixed) |
| [013](ADR-013-session-metadata-replace.md) | A backed-up session's metadata.json can be replaced | Accepted, built, deployed 2026-10-01 | TD-150 |
| [014](ADR-014-session-app-tag.md) | Cloud sessions are tagged with the app that backed them up | Accepted, built, deployed 2026-10-01; cross-app reconcile 2026-10-13 (semperdic-app) | semperdic-app TD-153 (closed) |
| [015](ADR-015-package-layout.md) | Feature subpackages of about 15 files and files of about 500 lines; workers, JNI classes and Activities keep their names | Accepted, built; amended 2026-10-03 | — |
| [016](ADR-016-work-that-outlives-the-activity.md) | Work that must outlive the Activity: ViewModel, an app-lifetime run, `NonCancellable` cleanup, or WorkManager | Accepted, built; amended 2026-10-19 (a lint or detekt check for rule 1 proposed) | — |
| [017](ADR-017-viewbinding-and-ui-kit.md) | ViewBinding for every screen, and one `ui/common` helper per UI job | Accepted, built | — |
| [018](ADR-018-error-convention.md) | One typed outcome per failure domain; cancellation is never a failure | Accepted, built | TD-41, TD-171 |
| [019](ADR-019-sempermechanics-app-id.md) | The app is `com.sempermechanics.semper`; "indic" leaves the code, and the engine submodule is `engine/` | Accepted, released 2026-10-17 | TD-176 |
| [020](ADR-020-backend-refusal-model.md) | Backend refusals are one exception (`errors.Refusal`) with one status table (`errors.STATUS`) | Accepted, built | TD-185, TD-186 |
| [021](ADR-021-retire-firestore-repo-facade.md) | Retire the `firestore_repo` facade; `app.repo` is a plain package and tests patch through `repo_view.patch` | Accepted, built | TD-190 |
| [022](ADR-022-semper-ui-plus-lab-tests.md) | Material Testing is Semper's UI plus two test choices; a fork change to a shared screen is gated on the test | Accepted, built | — |
| [022](ADR-022-rerun-as-copy.md) (semperdic-app) | A finished analysis is never overwritten; Home's Re-run copies its inputs into a new analysis | Accepted, built (here since the 2026-10-19 sync) | semperdic-app TD-211 (closed) |
| [023](ADR-023-signed-offline-entitlement.md) | A signed, time-limited entitlement (ES256 JWS, pinned by `kid`) the app verifies offline, with a bounded grace and clock-rollback detection; never a lock-out | Accepted, built; key not yet provisioned | TD-215, TD-219, TD-234, TD-235, TD-236, TD-238, TD-261 (TD-263–TD-266 closed 2026-10-18) |
| [024](ADR-024-account-anchored-entitlements.md) | Paid entitlements are anchored to the account and never entered in the app; the backend checks every cloud request; the app is consumption-only | Accepted, built (not merged or deployed); supersedes in part 007 and TD-259 | TD-274–TD-278, FI-24 (TD-259 and TD-272 closed 2026-10-18) |
| [025](ADR-025-test-type-extension-points.md) (semperdic-app) | Test types plug into Semper through seven no-op extension points (`ext.AppExtensions`); Material Testing becomes a `materialTesting` product flavour of semperdic-app, and this repo's backend, console, rules, contracts and ops copies are deleted | Proposed (mirrored 2026-10-19); would supersede in part 009 and 022 (`ADR-022-semper-ui-plus-lab-tests`) | semperdic-app TD-298; TD-305 (the sync it would end) |
| [026](ADR-026-licence-fanout-marker.md) | A licence-wide fan-out (revoke, terms edit) is recorded on the licence in its own write and finished by a delayed Cloud Task scheduled before it | Accepted, built (not merged or deployed) | semperdic-app TD-294 |
| [027](ADR-027-test-accounts.md) | Staff test accounts for QC and CI: a tagged, pre-approved, licensed login on a company domain, several phones per app, its own Drive tree, out of the demo counts, erased at its end | Accepted, backend built (not merged or deployed) | semperdic-app TD-313 |

Register IDs refer to [../ops/TECH_DEBT.md](../ops/TECH_DEBT.md); one no longer listed there is closed, and its history is in git. ADR-008, ADR-009,
ADR-011, ADR-012, ADR-013 and ADR-022 (`ADR-022-semper-ui-plus-lab-tests`) are material_testing's; ADR-010, ADR-014 and ADR-015 to ADR-021 are semperdic-app's (ADR-008's harness, ADR-011's viewer and ADR-013's route are in both). ADR-023 and ADR-024 are in both: each was written in semperdic-app and ported here with its code. ADR-026 and ADR-027 are semperdic-app's backend records, here with the backend copy the syncs bring (this repo deploys none of it). ADR-025 is semperdic-app's, mirrored here while it is proposed, since it decides this repo's future (edit it there first). semperdic-app's own ADR-022 is a different record (`ADR-022-rerun-as-copy`, Re-run as a copy), here too since the sync that brought its code in; the file names tell the two apart. The numbers are otherwise shared so they do not collide.
