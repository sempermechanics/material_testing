"""What in an OpenAPI change breaks a client that was built against the old one.

ADR-029 decision 3 names it: a path or method removed; a request field removed
while a client may still send it; a new required request field or parameter; a
narrower type, enum or bound on a request field. Response codes are compared
too: a client that branches on a status breaks when it stops coming.

`breaking_changes(old, new)` returns one line per finding, stable and sorted,
so a deliberate change can be acknowledged by copying its line into
`contracts/app-openapi.breaking.txt` (see `test_openapi_breaking.py`).
Additions, widenings and description text are not findings.
"""
from __future__ import annotations

import shutil
import subprocess
from pathlib import Path

BACKEND = Path(__file__).resolve().parents[1]
SNAPSHOT = BACKEND / "contracts" / "app-openapi.json"
ACKNOWLEDGED = BACKEND / "contracts" / "app-openapi.breaking.txt"
METHODS = ("get", "put", "post", "delete", "patch", "head", "options", "trace")
#: Bounds where a larger value is narrower, and where a smaller one is.
_LOWER_BOUNDS = ("minLength", "minimum", "exclusiveMinimum", "minItems")
_UPPER_BOUNDS = ("maxLength", "maximum", "exclusiveMaximum", "maxItems")


def _resolve(doc: dict, schema: dict | None) -> dict:
    seen = set()
    while isinstance(schema, dict) and "$ref" in schema and schema["$ref"] not in seen:
        ref = schema["$ref"]
        seen.add(ref)
        node = doc
        for part in ref.lstrip("#/").split("/"):
            node = node.get(part, {})
        schema = node
    return schema or {}


def _types(doc: dict, schema: dict) -> set[str]:
    """Every JSON type a value may take, `anyOf` / `oneOf` flattened."""
    schema = _resolve(doc, schema)
    alts = schema.get("anyOf") or schema.get("oneOf")
    if alts:
        return set().union(*(_types(doc, s) for s in alts))
    t = schema.get("type")
    if isinstance(t, list):
        return set(t)
    if t:
        return {t}
    return {"object"} if "properties" in schema else {"any"}


def _enum(doc: dict, schema: dict) -> set | None:
    schema = _resolve(doc, schema)
    alts = schema.get("anyOf") or schema.get("oneOf")
    if alts:
        values = [_enum(doc, s) for s in alts]
        if any(v is None and _types(doc, s) != {"null"} for v, s in zip(values, alts)):
            return None
        return set().union(*(v for v in values if v is not None))
    if "enum" in schema:
        return {repr(v) for v in schema["enum"]}
    if "const" in schema:
        return {repr(schema["const"])}
    return None


def _bounds(doc: dict, schema: dict) -> dict:
    schema = _resolve(doc, schema)
    alts = schema.get("anyOf") or schema.get("oneOf")
    if alts:
        out: dict = {}
        for s in alts:
            out.update(_bounds(doc, s))
        return out
    return {k: schema[k] for k in (*_LOWER_BOUNDS, *_UPPER_BOUNDS, "pattern") if k in schema}


def _object(doc: dict, schema: dict) -> dict:
    """The object alternative of a schema (an Optional model is anyOf[obj, null])."""
    schema = _resolve(doc, schema)
    for s in schema.get("anyOf") or schema.get("oneOf") or []:
        r = _resolve(doc, s)
        if "properties" in r:
            return r
    return schema


def _compare_value(old_doc, old, new_doc, new, where: str, out: list[str],
                   depth: int = 0) -> None:
    old_types, new_types = _types(old_doc, old), _types(new_doc, new)
    if "number" in new_types:
        new_types = new_types | {"integer"}  # every integer is a number
    if "any" not in new_types and not old_types <= new_types:
        out.append(f"{where}: type narrowed {sorted(old_types)} -> {sorted(new_types)}")
    old_enum, new_enum = _enum(old_doc, old), _enum(new_doc, new)
    if new_enum is not None and (old_enum is None or not old_enum <= new_enum):
        lost = sorted(old_enum - new_enum) if old_enum is not None else ["(any value)"]
        out.append(f"{where}: enum narrowed, no longer accepts {', '.join(lost)}")
    ob, nb = _bounds(old_doc, old), _bounds(new_doc, new)
    for key in _LOWER_BOUNDS:
        if key in nb and (key not in ob or nb[key] > ob[key]):
            out.append(f"{where}: {key} raised {ob.get(key)} -> {nb[key]}")
    for key in _UPPER_BOUNDS:
        if key in nb and (key not in ob or nb[key] < ob[key]):
            out.append(f"{where}: {key} lowered {ob.get(key)} -> {nb[key]}")
    if "pattern" in nb and nb.get("pattern") != ob.get("pattern"):
        out.append(f"{where}: pattern changed")
    if depth < 6 and "object" in old_types and "object" in new_types:
        _compare_object(old_doc, _object(old_doc, old), new_doc, _object(new_doc, new),
                        where, out, depth + 1)


def _compare_object(old_doc, old: dict, new_doc, new: dict, where: str, out: list[str],
                    depth: int) -> None:
    old_props, new_props = old.get("properties") or {}, new.get("properties") or {}
    old_req, new_req = set(old.get("required") or []), set(new.get("required") or [])
    for name in sorted(old_props):
        if name not in new_props:
            # Refused by a model that forbids extras, silently dropped by one
            # that does not: either way a client still sending it is broken.
            out.append(f"{where}.{name}: request field removed")
            continue
        _compare_value(old_doc, old_props[name], new_doc, new_props[name],
                       f"{where}.{name}", out, depth)
    for name in sorted(new_req - old_req):
        out.append(f"{where}.{name}: request field now required")


def _operations(doc: dict) -> dict[tuple[str, str], dict]:
    return {(m.upper(), path): op
            for path, item in (doc.get("paths") or {}).items()
            for m, op in item.items() if m in METHODS}


def _params(op: dict) -> dict[tuple[str, str], dict]:
    return {(p.get("in", ""), p.get("name", "")): p for p in op.get("parameters") or []}


def _json_body(op: dict) -> dict | None:
    body = op.get("requestBody")
    if not body:
        return None
    content = body.get("content") or {}
    media = content.get("application/json") or next(iter(content.values()), {})
    return {"required": bool(body.get("required")), "schema": media.get("schema") or {}}


def breaking_changes(old: dict, new: dict) -> list[str]:
    """Every change from `old` to `new` that can break a client of `old`."""
    out: list[str] = []
    old_ops, new_ops = _operations(old), _operations(new)
    for key in sorted(old_ops):
        method, path = key
        if key not in new_ops:
            out.append(f"{method} {path}: operation removed")
            continue
        o, n = old_ops[key], new_ops[key]
        where = f"{method} {path}"
        for code in sorted(set(o.get("responses") or {}) - set(n.get("responses") or {})):
            out.append(f"{where}: response {code} removed")
        op_, np_ = _params(o), _params(n)
        for (place, name), param in sorted(np_.items()):
            was = op_.get((place, name))
            if param.get("required") and not (was and was.get("required")):
                out.append(f"{where}: {place} parameter {name} now required")
            if was:
                _compare_value(old, was.get("schema") or {}, new, param.get("schema") or {},
                               f"{where} {place} {name}", out)
        ob, nb = _json_body(o), _json_body(n)
        if nb and nb["required"] and not (ob and ob["required"]):
            out.append(f"{where}: request body now required")
        if ob and nb:
            _compare_value(old, ob["schema"], new, nb["schema"], f"{where} body", out)
    return sorted(set(out))


def acknowledged() -> set[str]:
    """The findings a branch declares on purpose, one per line; `#` comments."""
    if not ACKNOWLEDGED.is_file():
        return set()
    lines = ACKNOWLEDGED.read_text(encoding="utf-8").splitlines()
    return {line.strip() for line in lines if line.strip() and not line.lstrip().startswith("#")}


def snapshot_at(ref: str) -> str | None:
    """The committed snapshot at a git ref, or None when git or the ref is not there."""
    try:
        rel = SNAPSHOT.relative_to(BACKEND.parent).as_posix()
        git = shutil.which("git")
        if git is None:
            return None
        done = subprocess.run([git, "show", f"{ref}:{rel}"], cwd=BACKEND,  # noqa: S603 - argv is git show of this repo's own file at a ref, no shell
                              capture_output=True, timeout=30, check=False)
    except (OSError, ValueError, subprocess.TimeoutExpired):
        return None
    return done.stdout.decode("utf-8") if done.returncode == 0 else None
