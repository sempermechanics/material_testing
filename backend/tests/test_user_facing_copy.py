"""Backend text a person reads: the tiers are "Demo" and "Licensed"
(ADDENDUM-3), the product is "semper" (ADDENDUM-4), a phone is a phone
(readiness AE3-101), and screens are named as the app names them (AE3-118).

The wire codes before a colon are unchanged; only the sentences are pinned.
"""
from app import errors, repo
from app.services import account as account_service
from app.services import sessions as session_service


def test_the_quota_remedies_name_the_demo_tier():
    for reason in (repo.INACTIVE_LICENCE_ENDED, repo.INACTIVE_NO_SEAT):
        sentence = session_service._QUOTA_REMEDY[reason]
        assert "the Demo limit applies" in sentence
        assert "demo" not in sentence.replace("Demo", "")


def test_the_device_quota_detail_says_phone_and_demo():
    detail = session_service._device_quota_detail(25)
    code, sentence = detail.split(": ", 1)
    assert code == errors.DEVICE_QUOTA_EXCEEDED
    assert sentence == ("Demo allows 25 analyses stored from one phone. Delete an older "
                        "analysis from this phone, or use a licence, to back up a new one.")


def test_the_export_names_the_apps_restore_screen(store, audited):
    store._data["users"] = {"u1": {"email": "a@b.c"}}
    note = account_service.export_header("u1")["artifactDownload"]["note"]
    assert note.endswith("or restore them in the app: Settings → Analyses data management.")
    assert "Restore screen" not in note


def test_the_staff_cap_refusal_names_the_tiers():
    import pytest
    from pydantic import ValidationError

    from app.models import AdminLicenseCreate

    with pytest.raises(ValidationError) as refused:
        AdminLicenseCreate(kind="individual", emailLock="a@b.c", maxAnalyses=1)
    assert "(the Demo allowance); leave it out for the Licensed default" in str(refused.value)
