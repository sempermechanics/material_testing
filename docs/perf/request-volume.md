# Backend requests per app open

Opening the app on the Pixel 6 on 2026-09-24 sent **19 backend requests in about
one second**: 10 × `GET /v1/config`, 8 × `GET /v1/sessions` and 1 × `GET /v1/me`
(production access log, one uid). Each one also started an App Check attestation,
which Firebase answered with "Too many attempts". This file records each change
that lowers that number, one lever per PR, with its measurements before and after.

Evidence labels: **[Measured]** means run and read here; **[Estimated]** means derived
from a measured count and a documented cost; **[Unknown]** means not yet measured.

## Why so many

- Home calls `refresh()` once for each finished upload/restore job that WorkManager
  still keeps (`HomeActivity.observeUploadFailures` / `observeRestoreProgress`). A new
  activity has not seen any of them yet, so N kept jobs start N + 1 reconciles at once.
- `CloudSync.reconcile` throttles to one check every 5 minutes, but it reads the
  timestamp at the start and writes it only after `listSessions` succeeds. Calls that
  start together all pass the throttle, and each one fetches config first.

## Pass 1: one reconcile at a time (#191)

**Lever:** redundant work elimination. A `Mutex` in `CloudSync.reconcile` runs
the calls one at a time. The first one lists the cloud and writes the timestamp. Each
later call then finds the timestamp fresh and the config known, and returns `Skipped`
without touching the network. A pull-to-refresh (`deep`) still always goes through;
it just waits for any call already running. If the pull takes the lock first, a
resume queued behind it finds the check fresh and skips, so the pull's listing is
the only one.

**Instrument:** `CloudSyncConcurrencyTest` starts K `reconcile(deep = false)` calls
together against `FakeCloudApi`, whose `listSessions` and `getConfig` each take
50 ms. It counts the calls. JVM, Robolectric SDK 34, Windows 11; 3 runs before, 13 after.

| Simultaneous reconciles (K) | listings / config reads before | after |
|--:|--:|--:|
| 2 | 2 / 2 | 1 / 1 |
| 8 | 8 / 8 | 1 / 1 |
| 32 | 32 / 32 | 1 / 1 |

[Measured] The counts were identical in every run at each size (spread 0):
before = K / K, after = 1 / 1 at every K. The test stays in the suite as the
regression guard.

**Amdahl ceiling.** Of the 19 requests per open, the reconciles account for
8 listings + 8 config reads = 16 (p ≈ 0.84), so the lever can at best cut requests
by 1 / (1 − p) ≈ 6.3× [Estimated].

**Per app open on the device**, with 7 kept jobs as on 2026-09-24:

| | before [Measured, 1 open] | after [Estimated] |
|---|--:|--:|
| `GET /v1/sessions` | 8 | 1 |
| `GET /v1/config` | 10 | 3 |
| `GET /v1/me` | 1 | 1 |
| **total** | **19** | **5** (3.8× fewer) |
| Firestore reads for listings and config (S sessions; listing ≈ S + 4, config ≤ 3) | ≤ 8S + 62 | ≤ S + 13 |

**Device confirmation: [Unknown].** It needs this build on the phone and a
`launch_counts.py` run before and after in the same hour. The run force-stops and
opens the app 5 times, then counts the uid's requests per route from the access log.

**Cost:** one private `Mutex`; no new dependency. The only behaviour change is
that concurrent reconciles now wait for each other instead of overlapping. A
waiting pull-to-refresh can take up to one extra listing's latency.
