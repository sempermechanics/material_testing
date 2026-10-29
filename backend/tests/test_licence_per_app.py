"""A licence per app (ADR-033): a licence that names its app lands in that
app's mirror on the account, is promised under that app's invite, counts once
per person per app, and gives its phones back when the person leaves it.

Repo level, against the in-memory store: mint, the invite claim on first
sign-in, attaching to an account that already exists, revoke, the staff edit
of `maxDevices`, a seat revoke, account erasure and the in-place convert that
an app's licence does not take."""
import pytest
from pydantic import ValidationError

import repo_view as repo
from app import apps, errors
from app.licenses import invite_id
from app.models import AdminLicenseCreate
from license_helpers import _mint_individual, _signed_in
from refusals import attempt

CLASSROOM = apps.SEMPER_CLASSROOM


def _phone(store, uid, device_id, app="semper"):
    """`device_id` registered ACTIVE to `uid` for `app`, as sign-in leaves it."""
    store._data.setdefault("devices", {})[device_id] = {
        "uid": uid, "status": "ACTIVE", "app": app}


def _user(store, uid="solo-1"):
    return {**store._data["users"][uid], "uid": uid}


def _acts(store, license_id):
    return store._data.get(f"licenses/{license_id}/activations", {})


def _both_apps(store, uid="solo-1", email="solo@lab.org"):
    """A Semper and a Semper Classroom licence for `email`, minted before the
    person signs in, then claimed on their first request."""
    store._data["users"] = {}
    semper = _mint_individual(email, app=apps.SEMPER)["license"]["id"]
    classroom = _mint_individual(email, app=CLASSROOM)["license"]["id"]
    repo.ensure_entitlement(_signed_in(store, uid, email), None)
    return semper, classroom


# ------------------------------------------------------------------ mint


def test_an_individual_mint_with_an_app_stamps_it_and_one_phone(store):
    minted = _mint_individual(app=CLASSROOM)
    stored = store._data["licenses"][minted["license"]["id"]]
    assert apps.canonical(stored["app"]) == CLASSROOM
    assert (stored["maxDevices"], stored["devicesActive"]) == (1, 0)
    assert minted["license"]["app"] == CLASSROOM
    assert minted["license"]["maxDevices"] == 1


def test_a_mint_without_an_app_stays_the_licence_from_before_the_split(store):
    license_id = _mint_individual()["license"]["id"]
    stored = store._data["licenses"][license_id]
    assert "app" not in stored and "maxDevices" not in stored


@pytest.mark.parametrize("seating, max_seats, want", [
    ("assigned", 5, 5),
    ("assigned", None, None),
    ("floating", 3, None),
])
def test_an_institution_pool_defaults_to_its_seats(store, seating, max_seats, want):
    """One phone per seat on an assigned licence; a floating licence counts
    leases, and no seat cap leaves the pool uncounted."""
    lic = repo.create_institution_license(
        domain_lock="university.edu", admin_emails=["it@university.edu"],
        created_by_uid="admin", max_seats=max_seats, seating=seating, app=apps.SEMPER,
    )["license"]
    assert store._data["licenses"][lic["id"]].get("maxDevices") == want


def test_max_devices_needs_an_app_and_an_assigned_licence():
    with pytest.raises(ValidationError):
        AdminLicenseCreate(emailLock="a@b.org", maxDevices=2)
    with pytest.raises(ValidationError):
        AdminLicenseCreate(kind="institution", domainLock="b.org", maxSeats=2,
                           seating="floating", app="semper", maxDevices=2)
    assert AdminLicenseCreate(emailLock="a@b.org", app="semperclassroom", maxDevices=3).maxDevices == 3


# ------------------------------------------------------- one licence per app


def test_one_licence_per_person_per_app(store):
    """A second Semper licence for the address is refused; a Classroom one is not."""
    store._data["users"] = {}
    _mint_individual("solo@lab.org", app=apps.SEMPER)
    assert repo.licence_held_by("solo@lab.org", app=apps.SEMPER)
    assert not repo.licence_held_by("solo@lab.org", app=CLASSROOM)


def test_a_licence_from_before_the_split_holds_for_every_app(store):
    store._data["users"] = {}
    _mint_individual("solo@lab.org")
    assert repo.licence_held_by("solo@lab.org", app=apps.SEMPER)
    assert repo.licence_held_by("solo@lab.org", app=CLASSROOM)


def test_each_app_is_promised_under_its_own_invite(store):
    store._data["users"] = {}
    _mint_individual("solo@lab.org", app=apps.SEMPER)
    _mint_individual("solo@lab.org", app=CLASSROOM)
    invites = store._data["licenseInvites"]
    assert invite_id("solo@lab.org") in invites
    assert invite_id("solo@lab.org", CLASSROOM) in invites
    assert invite_id("solo@lab.org", CLASSROOM) != invite_id("solo@lab.org")


# ----------------------------------------------------------- the mirror slots


def test_first_sign_in_claims_both_apps_into_their_own_slots(store):
    semper, classroom = _both_apps(store)
    user = _user(store)
    assert user["licenseId"] == semper
    assert apps.read_field(user, "licenseId", CLASSROOM) == classroom
    assert apps.licence_view(user, apps.SEMPER)["licenseId"] == semper
    assert apps.licence_view(user, CLASSROOM)["licenseId"] == classroom
    assert repo.effective_mode(user, apps.SEMPER) == "licensed"
    assert repo.effective_mode(user, CLASSROOM) == "licensed"
    assert store._data.get("licenseInvites", {}) == {}


def test_a_semper_only_licence_leaves_classroom_on_demo(store):
    store._data["users"] = {}
    _mint_individual("solo@lab.org", app=apps.SEMPER)
    repo.ensure_entitlement(_signed_in(store, "solo-1", "solo@lab.org"), None)
    user = _user(store)
    assert repo.effective_mode(user, apps.SEMPER) == "licensed"
    assert repo.effective_mode(user, CLASSROOM) == "demo"


def test_a_classroom_licence_attaches_to_an_account_that_holds_semper(store):
    """Minted after the person signed in, it lands in Classroom's slot at
    mint, and Semper's licence is untouched."""
    store._data["users"] = {}
    semper = _mint_individual("solo@lab.org", app=apps.SEMPER)["license"]["id"]
    repo.ensure_entitlement(_signed_in(store, "solo-1", "solo@lab.org"), None)
    classroom = _mint_individual("solo@lab.org", app=CLASSROOM)["license"]["id"]
    user = _user(store)
    assert user["licenseId"] == semper
    assert apps.read_field(user, "licenseId", CLASSROOM) == classroom
    assert store._data["licenses"][classroom].get("redeemedByUid") == "solo-1"


def test_revoking_one_apps_licence_leaves_the_other(store):
    semper, classroom = _both_apps(store)
    repo.revoke_license(classroom, "admin")
    repo.finish_license_fanout(classroom)
    user = _user(store)
    assert user["licenseId"] == semper
    assert repo.effective_mode(user, apps.SEMPER) == "licensed"
    assert repo.effective_mode(user, CLASSROOM) == "demo"


# ------------------------------------------------------------- maxDevices edit


def test_max_devices_edit_needs_an_app(store):
    license_id = _mint_individual()["license"]["id"]
    lic = store._data["licenses"][license_id]
    assert repo.license_edit_error(lic, {"maxDevices": 2}) == errors.MAX_DEVICES_NEEDS_APP


def test_max_devices_cannot_drop_below_the_phones_active(store):
    license_id = _mint_individual(app=apps.SEMPER)["license"]["id"]
    lic = {**store._data["licenses"][license_id], "devicesActive": 2, "maxDevices": 3}
    assert repo.license_edit_error(lic, {"maxDevices": 1}) == errors.MAX_DEVICES_BELOW_ACTIVE
    assert repo.license_edit_error(lic, {"maxDevices": 2}) == ""


# ---------------------------------------------------- leaving gives phones back


def test_a_seat_revoke_frees_the_members_phones_from_the_pool(store):
    store._data["users"] = {}
    license_id = repo.create_institution_license(
        domain_lock="university.edu", admin_emails=["it@university.edu"],
        created_by_uid="admin", max_seats=2, app=apps.SEMPER,
    )["license"]["id"]
    _signed_in(store, "m1", "m1@university.edu")
    assert attempt(repo.add_institution_member, license_id, "m1@university.edu")[0] == ""
    _phone(store, "m1", "phone-m1")
    repo.activate_device("m1", "phone-m1", apps.SEMPER)
    assert store._data["licenses"][license_id]["devicesActive"] == 1

    assert repo.revoke_institution_seat(license_id, "m1", actor=repo.ACTOR_IT)

    assert store._data["licenses"][license_id]["devicesActive"] == 0
    assert _acts(store, license_id)["phone-m1"]["status"] == "deactivated"


def test_account_erasure_gives_back_every_apps_licence_and_its_phones(store):
    semper, classroom = _both_apps(store)
    _phone(store, "solo-1", "phone-s")
    _phone(store, "solo-1", "phone-c", app=CLASSROOM)
    repo.activate_device("solo-1", "phone-s", apps.SEMPER)
    repo.activate_device("solo-1", "phone-c", CLASSROOM)

    repo._give_back_license("solo-1")

    for license_id in (semper, classroom):
        lic = store._data["licenses"][license_id]
        assert lic.get("redeemedByUid") in (None, "")
        assert lic["devicesActive"] == 0
    assert invite_id("solo@lab.org") in store._data["licenseInvites"]
    assert invite_id("solo@lab.org", CLASSROOM) in store._data["licenseInvites"]


def test_an_apps_licence_does_not_convert_in_place(store):
    store._data["users"] = {}
    license_id = _mint_individual("solo@university.edu", app=apps.SEMPER)["license"]["id"]
    err, _ = attempt(repo.convert_to_institution, license_id, domain_lock="university.edu",
                     admin_emails=["it@university.edu"], max_seats=None,
                     seating="assigned", admin_uid="admin")
    assert err == errors.LICENSE_NOT_CONVERTIBLE
