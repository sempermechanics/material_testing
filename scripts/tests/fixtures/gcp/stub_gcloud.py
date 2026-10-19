#!/usr/bin/env python3
"""A fake `gcloud` (and `curl`, when invoked under that name) for the ops
scripts' smoke tests. It never reaches Google: every answer comes from
fixture files, and every call is appended to $STUB_LOG.

`gcloud`: the argument list (minus --project/--format/--quiet style flags'
values, kept verbatim) is matched against $STUB_FIXTURES/gcloud.json, a list
of {"match": [...words that must all appear in order...], "stdout": "...",
"stdout_file": "name", "exit": 0}. The first match wins; no match exits 1
with "NOT_FOUND" on stderr, as gcloud does for a missing resource.

`curl`: answers from $STUB_FIXTURES/curl.json, a list of {"method": "GET",
"url_contains": "...", "status": 200, "body": {...}}; it writes the body to
the -o file and prints the status as `-w '%{http_code}'` would.
"""
import json
import os
import sys
from pathlib import Path

FIX = Path(os.environ["STUB_FIXTURES"])
LOG = os.environ.get("STUB_LOG")


def log(line: str) -> None:
    if LOG:
        with open(LOG, "a", encoding="utf-8") as fh:
            fh.write(line + "\n")


def in_order(words, args):
    it = iter(args)
    return all(any(w == a or (w.endswith("*") and a.startswith(w[:-1])) for a in it) for w in words)


def gcloud(args):
    log("gcloud " + " ".join(args))
    rules = json.loads((FIX / "gcloud.json").read_text(encoding="utf-8"))
    for rule in rules:
        if in_order(rule["match"], args):
            out = rule.get("stdout", "")
            if "stdout_file" in rule:
                out = (FIX / rule["stdout_file"]).read_text(encoding="utf-8")
            sys.stdout.write(out)
            if rule.get("stderr"):
                sys.stderr.write(rule["stderr"])
            return int(rule.get("exit", 0))
    sys.stderr.write("ERROR: (gcloud) NOT_FOUND: stub has no answer for: " + " ".join(args) + "\n")
    return 1


def curl(args):
    method, out, url = "GET", None, None
    i = 0
    while i < len(args):
        a = args[i]
        if a == "-X":
            method = args[i + 1]
            i += 2
            continue
        if a == "-o":
            out = args[i + 1]
            i += 2
            continue
        if a in ("-H", "-w", "--data-binary"):
            i += 2
            continue
        if not a.startswith("-"):
            url = a
        i += 1
    log(f"curl {method} {url}")
    rules = json.loads((FIX / "curl.json").read_text(encoding="utf-8"))
    for rule in rules:
        if rule["method"] == method and rule["url_contains"] in (url or ""):
            if out:
                Path(out).write_text(json.dumps(rule.get("body", {})), encoding="utf-8")
            status = int(rule.get("status", 200))
            if "-w" in args:
                sys.stdout.write(str(status))
            return 22 if status >= 400 and "--fail" in args else 0
    if out:
        Path(out).write_text(json.dumps({"error": {"code": 404}}), encoding="utf-8")
    if "-w" in args:
        sys.stdout.write("404")
    return 22 if "--fail" in args else 0


if __name__ == "__main__":
    name = Path(sys.argv[0]).name
    sys.exit(curl(sys.argv[1:]) if name == "curl" else gcloud(sys.argv[1:]))
