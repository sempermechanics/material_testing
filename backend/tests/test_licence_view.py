"""Each app reads its own licence off the user document (ADR-033).

A licence belongs to one app. Semper's mirror is the unsuffixed fields every
account already has; Semper Classroom's is the same names suffixed, written
through `apps.written_fields` while TD-330 keeps the `*MaterialTesting` names
the stored truth. An account from before the split has one licence in the
unsuffixed fields and no `licenseApp`, and it still holds for both apps, so
nothing changes until the migration splits it.
"""
from datetime import datetime, timedelta, timezone

from app import apps
from app.repo.user_config import (
    effective_mode,
    entitlement_of,
    license_summary,
    resolve_user_config,
)

SC = apps.SEMPER_CLASSROOM

_SHARED = {
    "licenseId": "lic-shared",
    "mode": "licensed",
    "licenseKind": "individual",
    "licensePrefix": "SMP-SHARED",
    "maxSessions": 7,
}


def _classroom_mirror(**fields) -> dict:
    out: dict = {}
    for name, value in fields.items():
        out.update(apps.written_fields(name, SC, value))
    return out


def test_semper_reads_the_unsuffixed_fields_unchanged():
    assert apps.licence_view(_SHARED, apps.SEMPER) is _SHARED


def test_an_account_from_before_the_split_holds_its_licence_for_both_apps():
    view = apps.licence_view(_SHARED, SC)
    assert view["licenseId"] == "lic-shared"
    assert effective_mode(_SHARED, SC) == "licensed"
    assert effective_mode(_SHARED) == "licensed"


def test_a_split_account_without_a_classroom_licence_is_demo_in_classroom():
    user = {**_SHARED, "licenseApp": apps.SEMPER}
    view = apps.licence_view(user, SC)
    assert "licenseId" not in view
    assert "mode" not in view
    assert view["maxSessions"] == 7  # not a licence field: kept
    assert effective_mode(user, SC) == "demo"
    assert effective_mode(user) == "licensed"


def test_each_app_reads_its_own_licence():
    expires = datetime.now(timezone.utc) - timedelta(days=400)
    user = {
        **_SHARED,
        "licenseApp": apps.SEMPER,
        **_classroom_mirror(
            licenseId="lic-classroom",
            licenseApp=apps.stored_name(SC),
            mode="licensed",
            licenseKind="institution",
            licensePrefix="SMP-CLASS",
            licenseExpiresAt=expires,
            licenseGraceDays=0,
        ),
    }
    assert license_summary(user)["prefix"] == "SMP-SHARED"
    classroom = license_summary(user, SC)
    assert classroom["prefix"] == "SMP-CLASS"
    assert classroom["licenseKind"] == "institution"
    # The Classroom licence ended long ago; Semper's has no expiry.
    assert entitlement_of(user, SC).inactive_reason == "licence_ended"
    assert effective_mode(user, SC) == "demo"
    assert effective_mode(user) == "licensed"


def test_the_classroom_view_reads_the_legacy_field_while_td330_is_open():
    user = {"licenseApp": apps.SEMPER, "licenseIdMaterialTesting": "lic-old-name",
            "modeMaterialTesting": "licensed"}
    assert apps.licence_view(user, SC)["licenseId"] == "lic-old-name"
    assert effective_mode(user, SC) == "licensed"


def test_resolve_user_config_follows_the_app():
    user = {**_SHARED, "licenseApp": apps.SEMPER}
    assert resolve_user_config(user)["mode"] == "licensed"
    assert resolve_user_config(user, SC)["mode"] == "demo"
    assert resolve_user_config(user, SC)["licensePrefix"] == ""


def test_licence_patch_writes_semper_as_given():
    patch = {"licenseId": "x", "mode": "licensed", "updatedAt": 1}
    assert apps.licence_patch(patch) == patch


def test_licence_patch_suffixes_only_mirror_fields_for_classroom():
    out = apps.licence_patch({"licenseId": "x", "mode": "licensed", "updatedAt": 1}, SC)
    assert out == {
        "licenseIdSemperClassroom": "x",
        "licenseIdMaterialTesting": "x",
        "modeSemperClassroom": "licensed",
        "modeMaterialTesting": "licensed",
        "updatedAt": 1,
    }


def test_a_patched_classroom_mirror_reads_back_through_the_view():
    user = {**_SHARED, "licenseApp": apps.SEMPER}
    user.update(apps.licence_patch({"licenseId": "lic-c", "mode": "licensed",
                                    "licensePrefix": "SMP-C"}, SC))
    assert license_summary(user, SC)["prefix"] == "SMP-C"
    assert license_summary(user)["prefix"] == "SMP-SHARED"
