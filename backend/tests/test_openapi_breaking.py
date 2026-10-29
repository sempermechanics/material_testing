"""A breaking change to the API is declared, not waved through (ADR-029,
readiness TE3-105).

`test_openapi_snapshot.py` fails on any byte of difference, and
`UPDATE_OPENAPI_SNAPSHOT=1` rewrites the file, so on its own nothing told a
reviewer whether the diff breaks a shipped client. These tests classify it
(`openapi_diff.breaking_changes`): a removed path or method, a removed or newly
required request field or parameter, a narrower type / enum / bound on a
request field, or a removed response code fails unless its line is listed in
`contracts/app-openapi.breaking.txt`, the explicit marker a PR carries when it
breaks a client on purpose (after shipping the client change first, or with a
compatibility window).

Two comparisons:
- the app now against the committed snapshot, so an unregenerated breaking
  change says that it is one (beside the byte diff of the snapshot test);
- the committed snapshot against the base branch's (`OPENAPI_BASE_REF`,
  default `origin/main`), which catches a snapshot regenerated over a breaking
  change. It needs that ref in the checkout; where it is absent (a shallow
  clone) it is skipped and says so.
"""
from __future__ import annotations

import copy
import json
import os

import pytest

import openapi_diff
from openapi_diff import breaking_changes

BASE_ENV = "OPENAPI_BASE_REF"


def _doc(paths: dict, schemas: dict | None = None) -> dict:
    return {"openapi": "3.1.0", "paths": paths, "components": {"schemas": schemas or {}}}


def _post(schema_ref: str = "#/components/schemas/Body", *, required: bool = True,
          params: list | None = None, responses: tuple = ("200", "422")) -> dict:
    return {"post": {
        "parameters": params or [],
        "requestBody": {"required": required,
                        "content": {"application/json": {"schema": {"$ref": schema_ref}}}},
        "responses": {code: {"description": code} for code in responses},
    }}


_BODY = {"type": "object", "additionalProperties": False, "required": ["email"],
         "properties": {
             "email": {"type": "string", "maxLength": 320, "minLength": 3},
             "mode": {"enum": ["demo", "licensed"], "type": "string"},
             "note": {"anyOf": [{"type": "string", "maxLength": 200}, {"type": "null"}]},
             "count": {"type": "integer", "minimum": 0},
         }}


def _base() -> dict:
    return _doc({"/v1/x": _post(), "/v1/y": {"get": {"responses": {"200": {}}}}},
                {"Body": copy.deepcopy(_BODY)})


def _changed(edit) -> list[str]:
    new = _base()
    edit(new)
    return breaking_changes(_base(), new)


# ------------------------------------------------------------- the classifier


def test_no_change_is_not_breaking():
    assert breaking_changes(_base(), _base()) == []


@pytest.mark.parametrize("edit,finding", [
    (lambda d: d["paths"].pop("/v1/y"), "GET /v1/y: operation removed"),
    (lambda d: d["paths"]["/v1/x"].update(put=d["paths"]["/v1/x"].pop("post")),
     "POST /v1/x: operation removed"),
    (lambda d: d["components"]["schemas"]["Body"]["properties"].pop("note"),
     "POST /v1/x body.note: request field removed"),
    (lambda d: d["components"]["schemas"]["Body"]["required"].append("mode"),
     "POST /v1/x body.mode: request field now required"),
    (lambda d: d["components"]["schemas"]["Body"]["properties"]["mode"]["enum"].remove("demo"),
     "POST /v1/x body.mode: enum narrowed, no longer accepts 'demo'"),
    (lambda d: d["components"]["schemas"]["Body"]["properties"]["count"].update(type="boolean"),
     "POST /v1/x body.count: type narrowed ['integer'] -> ['boolean']"),
    (lambda d: d["components"]["schemas"]["Body"]["properties"]["email"].update(maxLength=100),
     "POST /v1/x body.email: maxLength lowered 320 -> 100"),
    (lambda d: d["components"]["schemas"]["Body"]["properties"]["count"].update(minimum=1),
     "POST /v1/x body.count: minimum raised 0 -> 1"),
    (lambda d: d["components"]["schemas"]["Body"]["properties"]["note"]["anyOf"].pop(),
     "POST /v1/x body.note: type narrowed ['null', 'string'] -> ['string']"),
    (lambda d: d["paths"]["/v1/x"]["post"]["responses"].pop("200"),
     "POST /v1/x: response 200 removed"),
    (lambda d: d["paths"]["/v1/x"]["post"]["parameters"].append(
        {"in": "query", "name": "page", "required": True, "schema": {"type": "string"}}),
     "POST /v1/x: query parameter page now required"),
])
def test_each_kind_of_breaking_change_is_found(edit, finding):
    assert finding in _changed(edit)


@pytest.mark.parametrize("edit", [
    lambda d: d["paths"].update({"/v1/z": {"get": {"responses": {"200": {}}}}}),
    lambda d: d["components"]["schemas"]["Body"]["properties"].update(extra={"type": "string"}),
    lambda d: d["components"]["schemas"]["Body"]["required"].remove("email"),
    lambda d: d["components"]["schemas"]["Body"]["properties"]["email"].pop("maxLength"),
    lambda d: d["components"]["schemas"]["Body"]["properties"]["mode"]["enum"].append("trial"),
    lambda d: d["components"]["schemas"]["Body"]["properties"]["count"].update(type="number"),
    lambda d: d["components"]["schemas"]["Body"].update(description="new words"),
    lambda d: d["paths"]["/v1/x"]["post"]["responses"].update({"409": {}}),
    lambda d: d["paths"]["/v1/x"]["post"]["parameters"].append(
        {"in": "query", "name": "page", "required": False, "schema": {"type": "string"}}),
], ids=["new-path", "new-optional-field", "field-optional", "maxLength-dropped",
        "enum-widened", "integer-to-number", "description", "new-response", "optional-param"])
def test_additions_and_widenings_are_not_breaking(edit):
    assert _changed(edit) == []


# ------------------------------------------------------------ the real API


def _snapshot() -> dict:
    return json.loads(openapi_diff.SNAPSHOT.read_text(encoding="utf-8"))


def _undeclared(findings: list[str]) -> list[str]:
    return sorted(set(findings) - openapi_diff.acknowledged())


def _explain(findings: list[str], against: str) -> str:
    return (f"Breaking API changes against {against} (ADR-029). Ship the client change "
            "first or keep a compatibility window, then declare each line in "
            "backend/contracts/app-openapi.breaking.txt and say so in the PR:\n  "
            + "\n  ".join(findings))


def test_the_app_makes_no_undeclared_breaking_change_to_the_snapshot():
    from app.main import app

    app.openapi_schema = None
    findings = _undeclared(breaking_changes(_snapshot(), app.openapi()))
    assert not findings, _explain(findings, "the committed snapshot")


def test_the_snapshot_makes_no_undeclared_breaking_change_to_the_base_branch():
    ref = os.environ.get(BASE_ENV) or "origin/main"
    base = openapi_diff.snapshot_at(ref)
    if base is None:
        pytest.skip(f"{ref} is not in this checkout (set {BASE_ENV} or fetch it)")
    findings = _undeclared(breaking_changes(json.loads(base), _snapshot()))
    assert not findings, _explain(findings, ref)


def test_the_marker_lists_only_findings():
    """A declared line that names no real kind of finding is a typo that would
    declare nothing."""
    for line in openapi_diff.acknowledged():
        assert ": " in line and line.split(" ", 1)[0] in {m.upper() for m in openapi_diff.METHODS}, line
