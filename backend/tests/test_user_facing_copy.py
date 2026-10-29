"""Backend text a person reads: the tiers are "Demo" and "Licensed"
(ADDENDUM-3), the product is "semper" (ADDENDUM-4), a phone is a phone
(readiness AE3-101), and screens are named as the app names them (AE3-118).

The wire codes before a colon are unchanged; only the sentences are pinned.
"""
import ast
import pathlib
import re

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


def test_the_restore_refusal_names_the_demo_tier():
    code, sentence = errors.feature_not_licensed_detail().split(": ", 1)
    assert code == errors.FEATURE_NOT_LICENSED
    # The app's own restore_not_licensed string says it the same way.
    assert sentence == "Restore isn't available on Demo."


_LOG_METHODS = frozenset({"debug", "info", "warning", "error", "exception", "critical", "log"})


def _sentences_outside_logs_and_docstrings(path):
    """Every string literal in `path` that reads as a sentence (has a space),
    minus docstrings and the arguments of `log.<level>(...)` calls: those are
    for operators, not for the person the backend answers."""
    tree = ast.parse(path.read_text(encoding="utf-8"))
    skip = set()
    for node in ast.walk(tree):
        if isinstance(node, (ast.Module, ast.FunctionDef, ast.AsyncFunctionDef, ast.ClassDef)):
            first = node.body[0] if node.body else None
            if isinstance(first, ast.Expr) and isinstance(first.value, ast.Constant):
                skip.add(id(first.value))
        if (isinstance(node, ast.Call) and isinstance(node.func, ast.Attribute)
                and node.func.attr in _LOG_METHODS):
            skip.update(id(inner) for arg in node.args for inner in ast.walk(arg))
    for node in ast.walk(tree):
        if (isinstance(node, ast.Constant) and isinstance(node.value, str)
                and id(node) not in skip and " " in node.value.strip()):
            yield node.lineno, node.value


def test_no_backend_sentence_writes_a_tier_in_lower_case():
    """ADDENDUM-3: every message table (errors.py, the session quota remedies,
    the model validators, the mails) names the tiers "Demo" and "Licensed".
    Identifiers, enum values and wire codes are single words with no space, so
    they are not sentences here; log lines are skipped."""
    lower_tier = re.compile(r"\b(?:demo|licensed|free tier|free plan|paid tier)\b")
    app_dir = pathlib.Path(errors.__file__).parent
    found = [
        f"{path.relative_to(app_dir.parent)}:{line}: {text!r}"
        for path in sorted(app_dir.rglob("*.py"))
        for line, text in _sentences_outside_logs_and_docstrings(path)
        if lower_tier.search(text)
    ]
    assert found == []
