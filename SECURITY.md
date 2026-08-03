# Security Policy

## Supported versions

Security fixes are applied on the active development branches (`main`, `damodar`)
and on tagged releases when applicable.

## Reporting a vulnerability

Please **do not** open a public GitHub issue for security reports.

Email the maintainers with:

- A description of the issue and its impact
- Steps to reproduce or a proof of concept
- Affected commit / release if known

We will acknowledge receipt and work with you on a fix and disclosure timeline.

## Client configuration

`app/google-services.json` is a Firebase client config (API keys are expected to
be restricted by package name / SHA certificate fingerprints in the Google Cloud
console). Do not commit private signing keys, Cloud Run service-account JSON, or
backend secrets — those belong in CI secrets / environment variables.
