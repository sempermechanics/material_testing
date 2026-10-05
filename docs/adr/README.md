# Architecture decision records

One file per decision that is expensive to rediscover and easy to undo by
accident. Format: Status / Context / Decision / Options / Trade-offs /
Consequences / Action items. A superseded record stays; its status names the
record that replaced it.

| ADR | Title | Status | Register |
|-----|-------|--------|----------|
| [001](ADR-001-firestore-repo-package.md) | Split `firestore_repo.py` into a package behind a facade | Accepted, built | TD-53, TD-58 |
| [002](ADR-002-cloudapi-seam.md) | `CloudApi` interface seam for `SemperApi` | Accepted, built | TD-25 |
| [003](ADR-003-viewerargs-read-side.md) | `ViewerArgs.from` read side with a `SessionRecord` fallback | Accepted, built | TD-3, TD-61 |
| [004](ADR-004-runspec.md) | Immutable `RunSpec` built once at Compute | Accepted, built | FI-6, TD-61 |
| [005](ADR-005-wizard-process-death.md) | The wizard survives process death through a draft | Accepted, built | TD-26 |
| [006](ADR-006-gateway-deploy-job.md) | CI deploys the API Gateway after the Cloud Run promote | Accepted, built (not yet dispatched) | TD-27 |
| [007](ADR-007-licence-lifecycle.md) | Licence lifecycle: one per person, replace by revoke, delete into a 30-day hold | Accepted, built (TTL policies owed) | — |
| [008](ADR-008-startup-gates-phone-state.md) | Real-device startup gates check the phone's state; a trip is settled A/B | Accepted, built (references owed) | TD-135 |
| [009](ADR-009-material-testing-app-identity.md) | Material Testing is its own Android app on Semper's backend | Accepted, built (App Check owed) | TD-133 |
| [010](ADR-010-device-binding-per-app.md) | Device binding per app: one phone per app, not per account | Accepted, built | TD-137, TD-138 |
| [011](ADR-011-viewer-deformed-frame.md) | The viewer draws each frame on its own photo, at the displaced positions | Accepted, built | TD-139 |
| [012](ADR-012-tensile-strain-virtual-extensometer.md) | Tensile strain is the virtual extensometer's ΔL / L₀ | Accepted, built | TD-144 (fixed), TD-147 (fixed) |
| [013](ADR-013-session-metadata-replace.md) | A backed-up session's metadata.json can be replaced | Accepted, built, deployed 2026-10-01 | TD-150 |
| [014](ADR-014-session-app-tag.md) | Cloud sessions are tagged with the app that backed them up | Accepted, built, deployed 2026-10-01 (semperdic-app) | semperdic-app TD-153 |
| [015](ADR-015-package-layout.md) | Feature subpackages of about 15 files and files of about 500 lines; workers, JNI classes and Activities keep their names | Accepted, built; amended 2026-10-03 | — |
| [016](ADR-016-work-that-outlives-the-activity.md) | Work that must outlive the Activity: ViewModel, an app-lifetime run, `NonCancellable` cleanup, or WorkManager | Accepted, built | TD-165, TD-168 |
| [017](ADR-017-viewbinding-and-ui-kit.md) | ViewBinding for every screen, and one `ui/common` helper per UI job | Accepted, built | — |
| [018](ADR-018-error-convention.md) | One typed outcome per failure domain; cancellation is never a failure | Accepted, built | TD-41, TD-171 |
| [019](ADR-019-sempermechanics-app-id.md) | The app is `com.sempermechanics.semper`; "indic" leaves the code, and the engine submodule is `engine/` | Accepted, built (not released) | TD-176 |
| [020](ADR-020-sempermechanics-materialtesting-id.md) | Material Testing is `com.sempermechanics.materialtesting`; amends ADR-009's package | Accepted, built (Firebase app and `google-services.json` owed; not released) | — |

Register IDs refer to [../ops/TECH_DEBT.md](../ops/TECH_DEBT.md). ADR-008, ADR-009,
ADR-011, ADR-012, ADR-013 and ADR-020 are material_testing's; ADR-010, ADR-014 and ADR-015–019 are semperdic-app's (ADR-008's harness, ADR-011's viewer and ADR-013's route are in both). The numbers are shared so they do not collide.
