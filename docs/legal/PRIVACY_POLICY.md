# Semper — Privacy Policy

**Last updated:** 2026-09-24  
**Product:** Semper DIC Android app, optional cloud sync backend, and web console  
**Data controller (data fiduciary):** [OPERATOR LEGAL NAME], [REGISTERED ADDRESS], Chennai, Tamil Nadu, India  
**Privacy contact:** the support mailbox configured as `SUPPORT_EMAIL` for the deployment  
**Grievance Officer (Digital Personal Data Protection Act, 2023):** [GRIEVANCE OFFICER NAME], [GRIEVANCE OFFICER EMAIL]  

This policy describes personal data processed by Semper when you use the app
and, if enabled, the Semper cloud backend. Analysis itself runs **on-device**;
the cloud path is optional and only active when the app is built with an API
base URL.

## 1. Controllers and processors

| Role | Who |
|------|-----|
| Controller / data fiduciary | [OPERATOR LEGAL NAME], Chennai, India — the organization that distributes your build and operates the GCP/Firebase project |
| Subprocessors (typical) | Google (Firebase Auth, Firestore, Google Drive, Firebase Crashlytics, Cloud Logging / Cloud Run), Resend (transactional email for access-request notifications) |

No large-language-model or generative-AI provider is integrated. Subprocessors
are bound by written data-processing terms that require them to protect your
data and to process it only on our instructions.

The service is operated from India and is not offered to residents of the
European Union, the European Economic Area, or the United Kingdom. We
nevertheless apply the safeguards described here — a separate, withdrawable
consent for any use of your content beyond providing the service, purpose
limitation, data minimisation, the rights in section 5, and breach handling —
as our standard for every user.

## 2. Data we process

### 2.1 Account and authentication (Firebase Authentication)

- Email address, display name, and provider identifiers from Google / email sign-in (as configured for the Firebase project).
- Authentication tokens used to call the backend (not stored as long-lived
  secrets on the server beyond normal session verification).

### 2.2 Access control and devices (Firestore)

- User profile: uid, email, role, access status (PENDING / APPROVED / SUSPENDED),
  optional product-limit overrides, Drive folder pointer.
- Registered device: device id, public key material for attestation, model /
  OS / app version strings.
- Short-lived auth challenges (nonces) with TTL.

### 2.3 Analysis metadata and files (Firestore + Google Drive)

- Session metadata (specimen label, status, sizes, engine metrics you sync).
- File manifests (names, roles, checksums, Drive object ids).
- Binary artifacts (images, archives, reports, `.dat` / CSV) stored in a
  company Shared Drive under the Cloud Run service account — **bytes are
  uploaded by the device directly to Drive**, not through Cloud Run.

### 2.4 Diagnostics (Firebase Crashlytics and Analytics) — opt-in

- **Off by default, and nothing is collected until you agree.** Collection is
  disabled in the app manifest; the app asks once on first launch and you can
  change the answer at any time in **Settings → Your data → Send crash reports and usage data**.
  Declining, or switching it off later, also deletes any report still queued on
  the device.
- When enabled: crash reports and non-fatal diagnostics from the Android app,
  including device/app version metadata as provided by the Crashlytics SDK, plus
  coarse product usage events (for example sign-in outcome, analysis started /
  completed with duration and frame-count buckets, cloud upload/restore
  outcomes, export, feedback opened). Event parameters are enums and buckets
  only — not emails, session ids, specimen names, or file contents. R8
  mapping files are retained by operators for deobfuscation and are not
  published.
- Diagnostics never include your images, measurement results, specimen names, or
  file contents.

### 2.5 Operational logs (Cloud Logging / Error Reporting)

- Structured request logs: timestamp, request id, path, status, latency,
  opaque error codes, and uid/device id when resolved. Tokens, signatures, and
  upload URIs are not logged.

### 2.6 Notifications (Resend)

- When a new account is created in PENDING status, support may receive an email
  containing the applicant’s email, display name, sign-in provider, and user id
  so an admin can approve access.

### 2.7 Audit trail

- Append-only `audit_logs` in Firestore record security-relevant actions
  (auth denials, approvals, deletes, exports). They intentionally retain the
  fact of actions after account erasure and do **not** store analysis content.

### 2.8 Product improvement — only with your separate consent

- **Your separate choice.** The app asks, as its own option next to (not
  inside) the Terms acceptance, whether your synced analysis content may be
  used to improve Semper. The option is presented pre-selected; untick it to
  decline. Declining has no effect on the service you receive. You can change your
  answer at any time in **Settings → Your data → Use my data to improve
  Semper**, or by emailing the privacy contact.
- **What is used when you consent:** analysis content you have synced to the
  cloud — images, `.dat` displacement/strain fields, reports, session metadata
  (specimen label, parameters, engine metrics) — and metrics derived from them.
  Content that stays on your device and is never synced is never used.
- **How it is used:** to test, tune, validate, benchmark, and improve the
  correlation engine, the app, and the service (for example regression
  datasets, parameter tuning, and accuracy studies).
- **Safeguards:** a pseudonymised copy is kept in a separate improvement
  dataset with restricted access; account identifiers are replaced by an
  internal key; specimen labels and free-text fields are stripped where they
  are not needed; the dataset is never published in a form that identifies
  you or your specimens and is never sold or licensed to third parties.
- **Withdrawal and retention:** withdrawing consent stops new use immediately.
  Your content is deleted from the improvement dataset within 30 days of
  withdrawal or account deletion. Independently of withdrawal, raw content is
  kept in the dataset for at most 36 months. Aggregated or derived results
  (tuned parameters, statistics) that no longer contain your content may be
  kept.
- **Aggregate metrics without consent:** de-identified, aggregated engine and
  operational metrics that contain no images, fields, or specimen names may be
  used to operate, secure, and improve the service on the basis of our
  legitimate interest. You can object using the same toggle.

### 2.9 Terms-acceptance and consent records

- When you accept the Terms of Service or change the product-improvement
  choice, we record the version accepted, the time, your user id, the
  registered device id, and the source (app or console). This is our evidence
  of the agreement and of your consent choices; it is included in your data
  export and is retained with the audit trail.

## 3. Purposes and legal bases (summary)

| Purpose | Examples | Basis (typical) |
|---------|----------|-----------------|
| Provide the product | Sign-in, sync, restore, quotas | Contract / legitimate interest |
| Access control | Pending approval, admin approve/revoke | Legitimate interest / compliance |
| Security | Device attestation, rate limits, audit | Legitimate interest |
| Reliability (server) | Cloud Logging, readiness probes | Legitimate interest |
| Reliability (app diagnostics) | Crashlytics / Analytics crash reports | **Consent** — opt-in, withdrawable in Settings |
| Support onboarding | Resend access-request mail | Legitimate interest |
| Product improvement — your synced content | Regression datasets, tuning, accuracy studies (section 2.8) | **Consent** — separate option, pre-selected but declinable before continuing, withdrawable in Settings |
| Product improvement — aggregate metrics | De-identified engine/operational statistics | Legitimate interest — objection honoured via the same toggle |
| Contract and consent records | Terms version accepted, consent changes | Contract / legitimate interest (evidence of agreement) |
| Legal compliance | Responding to lawful requests, tax and accounting records | Legal obligation |

Where the law of your country names different bases, the closest equivalent
applies. We do not make decisions about you based solely on automated
processing that produce legal or similarly significant effects.

## 3A. Where your data is processed and international transfers

Cloud data is stored in the Google Cloud region selected by the operator for
your deployment ([GCP REGION, e.g. asia-south1 (Mumbai)]) and may be processed
by Google in other regions for redundancy and support. Transactional email is
processed by Resend in the United States. Where data leaves India or your
country, we rely on the subprocessors' contractual data-protection commitments
and comply with applicable cross-border transfer rules, including those under
the Digital Personal Data Protection Act, 2023, and any restrictions notified
by the Government of India.

## 4. Retention

| Data | Retention |
|------|-----------|
| Firebase Auth account | Until you delete the account or an admin removes it |
| Firestore profile, devices, sessions, file docs | Until account/session erasure via the app/API |
| Drive artifacts | Deleted with session or account erasure (Shared Drive trash may retain per Workspace policy) |
| Crashlytics | Per Firebase project retention settings (**UNKNOWN** until verified in console) |
| Cloud Logging | Per GCP log retention (**UNKNOWN** until verified; default often 30 days) |
| Resend message content | Per Resend retention (**UNKNOWN** until verified) |
| Audit logs | Retained after erasure for security/compliance; not included in user export of analysis content |

Scheduled Firestore exports / PITR, where enabled, follow
[FIRESTORE_DATA_PROTECTION.md](../backend/FIRESTORE_DATA_PROTECTION.md).

## 5. Your rights

You have the following rights over your personal data, subject to applicable
law. We answer requests within 30 days (extendable where the law allows for
complex requests, and we will tell you if so). To protect your account we
verify requests through the app's device attestation or by re-authentication.

- **Access** — obtain a copy of the personal data we hold about you and
  information about how it is processed (use the export below).
- **Correction** — have inaccurate or incomplete data corrected (profile
  fields can be edited in the app or by request).
- **Erasure** — have your account and cloud content deleted (see below).
- **Restriction and objection** — ask us to limit processing, and object to
  processing based on legitimate interest, including the aggregate-metrics use
  in section 2.8.
- **Portability** — receive your data in a structured, machine-readable format
  (the export below is JSON).
- **Withdraw consent** — at any time, for diagnostics (section 2.4) and for
  product improvement (section 2.8), without affecting the lawfulness of
  processing before withdrawal.
- **Complain** — lodge a complaint with the data-protection authority that has
  jurisdiction over you. In India this is the Data Protection Board of India,
  after first raising the grievance with our Grievance Officer.

**India (Digital Personal Data Protection Act, 2023).** You may access,
correct, update, and erase your personal data, obtain grievance redressal
through the Grievance Officer named above within the statutory period, and
nominate a person to exercise these rights if you die or are incapacitated.

**Other jurisdictions.** If the law where you live gives you equivalent rights
(for example, rights to know, delete, or correct, and non-discrimination for
exercising them), we honour them on request. We do not sell personal data and
do not share it for cross-context behavioural advertising.

### 5.1 Export and deletion

- **Export:** In the app, **Settings → Your data → Download my cloud account
  data**. (Directly: authenticated, device-attested `GET /v1/me/export`, which
  returns profile, devices, and complete session manifests — `complete: true` is
  written last, so a truncated download is detectable.) Binary artifacts are
  downloaded via `GET /v1/files/{id}/content` (or the app Restore flow).
- **Delete session:** Device-attested `DELETE /v1/sessions/{id}` removes Drive
  folder + Firestore metadata for that analysis.
- **Delete account:** Device-attested `DELETE /v1/me` removes the Drive user
  subtree and Firestore user/session/device/file docs. **Audit logs remain.**
- **Limits:** Third-party processor retention (Crashlytics, Logging, Resend,
  Workspace trash) may outlive application erasure until those systems’ own
  retention/TTL elapse. Export does not include other users’ data or raw
  Cloud Logging streams. The export includes your terms-acceptance and
  consent records (section 2.9).

## 6. Sharing

Data is shared with subprocessors above to operate the service. It is not sold.
Admin operators of your deployment can approve users and view operational logs
according to project IAM.

## 6A. Data breaches

If a breach of security affects your personal data, we will notify the
competent authority within the period required by law (in India, as prescribed
under the Digital Personal Data Protection Act, 2023) and will inform affected
users without undue delay, describing the nature of the breach, the likely
consequences, and the measures taken.

## 7. Security (summary)

TLS in transit (Cloud Run / Gateway), deny-all client Firestore rules (server
SDK only), device attestation for high-consequence mutations, rate limits,
security headers, and opaque client error bodies on Cloud Run. See
[CLOUD_ARCHITECTURE_GCP.md](../backend/CLOUD_ARCHITECTURE_GCP.md).

## 8. Children

Semper is offered to users aged 16 and older. A user under 18 (or under the age
of majority where they live) may use it only with the agreement of a parent or
legal guardian (Terms §1.3). We do not knowingly process the personal data of
anyone under 16; if you believe someone under 16 has created an account,
contact us and we will delete it.

## 9. Changes

Material changes will update the “Last updated” date and will be announced
in-app or in release notes. Any change that would expand consent-based
processing (sections 2.4 and 2.8) is not applied to you until you consent to
it again in the app.

## 10. Contact

Use the in-app support / help action or the configured support email for privacy
requests (export, deletion, access questions). Grievances under the Digital
Personal Data Protection Act, 2023 go to the Grievance Officer named at the top
of this policy.
