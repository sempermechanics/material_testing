# ADR-006: CI deploys the API Gateway after the Cloud Run promote

**Status:** Accepted
**Date:** 2026-09-23
**Deciders:** backend owner, GCP project owner (IAM grant)

## Context

Production traffic reaches Cloud Run (`semper-api`) only through API Gateway
(`semper-gw`), whose ESPv2 config is an allowlist: a route missing from the
live config returns 404 with nothing in the logs. `backend/gateway/openapi.yaml`
is checked against the routers by `tests/test_gateway_parity.py`, but moving
the *live* gateway to a new config is a manual runbook
(`docs/backend/BACKEND_SETUP_GCP.md`, "Redeploying the gateway"). A route can be
merged, tested and deployed to Cloud Run while the gateway still 404s it
(TD-27) — which has bitten twice.

The runbook itself has three faults found in the 2026-09-23 audit:

1. `FIREBASE_PROJECT_ID` is never set in the redeploy block; an empty value
   passes the placeholder check and yields issuer
   `https://securetoken.google.com/`, which rejects every token.
2. `NEW_CFG=v$(date +%Y%m%d)` collides on a second deploy the same day.
3. `grep … && exit 1` as a step's last line exits 1 when **no** placeholder
   remains, i.e. on success.

`deploy-backend.yml` is dispatch-only, already authenticates by Workload
Identity Federation, and runs deploy → smoke → promote → rollback as steps of
one `deploy` job. Only production has a gateway.

## Decision

Add a `gateway` job to `deploy-backend.yml`:

- `needs: deploy`, same `environment`, its own WIF auth step;
  `if: inputs.environment == 'production'` until staging has a gateway.
- Input `gateway_mode: dry-run | apply`, default **`dry-run`**.
- Steps: resolve `RUN_URL` (job output from `deploy`), `MANAGED_SERVICE`
  (`gcloud api-gateway apis describe`), `vars.FIREBASE_PROJECT_ID`; fail if any
  is empty. Render `openapi.generated.yaml`; `if grep -qE '__[A-Z_]+__' …; then
  exit 1; fi`. Fetch the live config's spec and **diff**; if identical, stop.
  In `dry-run`, print the diff and stop.
- In `apply`: record `PREV_CFG`; create
  `v$(date -u +%Y%m%d%H%M)-${GITHUB_RUN_NUMBER}`; `gateways update`; verify
  unauthenticated `GET /v1/config` → 401 (not 404) and the CORS preflight →
  200 with `Access-Control-Allow-Origin`; on any failure,
  `gateways update --api-config=$PREV_CFG`.
- The runbook is rewritten to the same script as the manual fallback, with the
  three faults fixed.

Prerequisite (GCP project owner): the deploy service account needs
`roles/apigateway.admin` on the project and `roles/iam.serviceAccountUser` on
the gateway's backend service account (`indic-gw@…`). The first dispatch is
`dry-run`, run by the owner.

## Options considered

### A: `gateway` job in the deploy workflow (chosen)

| Dimension | Assessment |
|-----------|------------|
| Complexity | Low–medium |
| Cost | One job + one IAM grant |
| Scalability | Every deploy converges the gateway |
| Team familiarity | Same workflow, same WIF |

**Pros:** ordering is guaranteed (after promote); no-op when the spec is
unchanged; rollback built in. **Cons:** the deploy SA gains gateway admin.

### B: Separate dispatch workflow

**Cons:** keeps the human ordering step that TD-27 exists to remove.

### C: Replace API Gateway with a regional HTTPS load balancer + serverless NEG

**Pros:** also removes the Tokyo→Mumbai hop (TD-30). **Cons:** moves JWT
validation into Cloud Run or IAP; a migration, not a fix. Stays deferred.

## Trade-off analysis

A removes the silent-404 class of failure for the cost of one role grant. The
diff-and-skip makes it safe to run on every deploy; the dry-run default makes
the first run observational.

## Consequences

- Easier: a route that passes the parity test is reachable after the deploy.
- Harder: gateway permissions live on the deploy SA; review its bindings.
- Revisit: when staging gets a gateway, drop the production-only guard; if TD-30
  is taken on, this job is replaced.

## Action items

1. [x] `gateway` job with `gateway_mode`, diff-and-skip, verify, rollback.
2. [x] Rewrite the runbook; fix the three faults.
3. [ ] Owner: grant the two roles; dispatch `dry-run`, then `apply`.
4. [ ] Close TD-27 after the first successful `apply`.

## As built (2026-09-24)

- `deploy-backend.yml` has a `gateway` job: `needs: deploy`, production
  and `main` only, `environment: production`, and its own WIF auth. The
  `deploy` job exports `service` for it.
- The `gateway_mode` input is `dry-run` (default) or `apply`. Both render
  `openapi.generated.yaml` and fail on an empty `RUN_URL`,
  `FIREBASE_PROJECT_ID` or `MANAGED_SERVICE`, or on a leftover `__X__`.
  Both diff the spec against the live config's document (`api-configs
  describe --view=FULL`) and write the diff to the job summary. **Only
  `apply`** creates `v<UTC minute>-<run number>`, switches the gateway,
  verifies from outside, and rolls back to the previous config if the
  verification fails.
- No change to the spec means the job skips the create.
- Names default to today's resources: `semper-gw`, API `semper-api`,
  `asia-northeast1`, `indic-gw@PROJECT`. Repo variables `GATEWAY_ID`,
  `GATEWAY_API`, `GATEWAY_REGION` and `GATEWAY_SA` override them.
- The CORS check sends the first origin in `CONSOLE_ORIGINS`.
- **Not run yet.** No one has dispatched it; it needs the IAM grant (item
  3). Whether the live config's stored document is byte-equal to a fresh
  render has not been checked either. The first `dry-run` shows it: a diff
  of only whitespace or ordering means the skip would never fire, which is
  harmless, since `apply` then just creates an identical config.
