"""The lifecycle status strings this service stores and returns.

The sibling of `errors.py`. Where that module names what goes out as
`{"detail": …}`, this one names the `status` values written to Firestore and
echoed to the client -- a wire vocabulary in exactly the same sense, because the
Android client branches on them (`UploadWorkOutcomes.resumeKindFor` decides
whether a backup resumes, waits, or restarts from a session's status).

They were bare literals in nine modules until now. A typo in one of them is not
a crash: it is a session that never resumes, or a device that silently fails an
ACTIVE check. `tests/test_wire_vocabulary.py` pins the client-branched subset
against the client's own copy so a rename on either side fails CI.

Values are unchanged from the literals they replace -- this is a naming change,
not a contract change.
"""

# --- Session lifecycle (sessions/{id}.status) -------------------------------
#: Cloud Tasks is still creating the Drive folder and the per-file upload URLs.
SESSION_PROVISIONING = "PROVISIONING"
#: Provisioning failed terminally; the client offers a retry.
SESSION_PROVISION_FAILED = "PROVISION_FAILED"
#: At least one file is uploading.
SESSION_UPLOADING = "UPLOADING"
#: Every file in the manifest has completed.
SESSION_COMPLETED = "COMPLETED"

#: Statuses that mean work is still outstanding, so a new backup of the same
#: analysis must not start a second session. Order preserved from the tuple
#: this replaces in firestore_repo.
IN_FLIGHT_SESSION_STATUSES = (SESSION_PROVISIONING, SESSION_UPLOADING)

# --- File lifecycle (files/{id}.status) -------------------------------------
FILE_PENDING = "PENDING"
FILE_COMPLETED = "COMPLETED"

# --- Device registration (devices/{id}.status) ------------------------------
#: The device may sign attested requests.
DEVICE_ACTIVE = "ACTIVE"
#: Access was revoked; the key must not verify again.
DEVICE_REVOKED = "REVOKED"
#: Another device took the account's binding, by registering or by a lock clear.
DEVICE_SUPERSEDED = "SUPERSEDED"

# --- Account access (users/{uid}.access_status) -----------------------------
#: Mirrors validation.AccessStatus, which is the request-side copy.
ACCESS_PENDING = "PENDING"
ACCESS_APPROVED = "APPROVED"
ACCESS_SUSPENDED = "SUSPENDED"

#: Session statuses the Android client branches on. Changing one of these needs
#: the matching edit to UploadWorkOutcomes.kt in the same commit --
#: tests/test_wire_vocabulary.py fails otherwise.
CLIENT_BRANCHED_SESSION = frozenset(
    {
        SESSION_PROVISIONING,
        SESSION_PROVISION_FAILED,
        SESSION_COMPLETED,
    }
)
