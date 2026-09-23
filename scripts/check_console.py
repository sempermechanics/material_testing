#!/usr/bin/env python3
"""Structural checks for the web consoles.

The consoles are the one part of this repository with no compiler and no
build step: four HTML pages that wire themselves to the DOM by element id and
to the backend by string path. Nothing else in the tree fails when one of
those strings goes stale, so the failure mode is a page that loads, looks
right, and silently does nothing — on the pages that mint and revoke
licences.

This is that missing gate. It checks the things a browser only discovers at
runtime, and a person only discovers in production:

1. No inline script or `on*=` handler on a console page. The `/console/**`
   Content-Security-Policy is `script-src 'self'` with no `'unsafe-inline'`,
   so the browser refuses to run inline code. Code in a page body is not
   "working but untidy" — it never executes.
2. Every module a page loads exists, and parses as an ES module (when node
   is on PATH; skipped, loudly, when it is not).
3. Every `$("id")` a module asks for is an id its own page defines.
4. The deploy-time placeholders are still placeholders. `deploy-console.sh`
   substitutes and restores them; a failed restore lands a live hostname in
   git, which is the footgun the console README warns about.
5. Every Hosting rewrite lands on a file that exists.
6. The two console CSP entries are character-for-character identical, which
   is what the comment beside them in `firebase.json` promises.
7. Every `/v1/...` path a console calls is declared in the API Gateway spec.
   ESPv2 is an allowlist: a route missing there is unreachable in production
   no matter what the backend serves.

Run: `python scripts/check_console.py`. Exit 1 on the first failure found,
after reporting all of them.
"""

from __future__ import annotations

import glob
import json
import os
import re
import shutil
import subprocess
import sys

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
HOSTING = os.path.join(ROOT, "firebase-hosting")
PUBLIC = os.path.join(HOSTING, "public")
CONSOLE = os.path.join(PUBLIC, "console")
GATEWAY = os.path.join(ROOT, "backend", "gateway", "openapi.yaml")

# The pages that carry a dashboard. `finishSignIn` and `finishReset` are auth
# continue-URLs served under the strict global policy and are not consoles.
PAGES = [
    os.path.join(CONSOLE, "index.html"),
    os.path.join(CONSOLE, "account", "index.html"),
    os.path.join(CONSOLE, "institution", "index.html"),
    os.path.join(CONSOLE, "operator", "index.html"),
]

failures: list[str] = []


def fail(where: str, message: str) -> None:
    failures.append(f"{os.path.relpath(where, ROOT)}: {message}")


def read(path: str) -> str:
    with open(path, encoding="utf-8") as handle:
        return handle.read()


# ---------------- 1 & 2: scripts are external, present and parse ----------


def check_scripts() -> None:
    node = shutil.which("node")
    if not node:
        print("note: node not on PATH — module syntax not checked", file=sys.stderr)

    for page in PAGES:
        html = read(page)

        for match in re.finditer(r"<script\b([^>]*)>(.*?)</script>", html, re.S):
            attrs, body = match.group(1), match.group(2)
            if body.strip():
                fail(page, "inline <script> body — the console CSP is "
                           "script-src 'self', so this never runs in production; "
                           "move it to a module file and load it with src=")
            src = re.search(r'src="([^"]+)"', attrs)
            if not src:
                continue
            module = os.path.normpath(os.path.join(os.path.dirname(page), src.group(1)))
            if not os.path.isfile(module):
                fail(page, f"loads {src.group(1)}, which does not exist")
            elif node:
                # Fed on stdin with an explicit module type. `node --check
                # <path>` looks like the obvious call and is not: for a bare
                # `.js` path Node 22 exits 0 on source that does not parse at
                # all, so the check would pass on anything.
                proc = subprocess.run(
                    [node, "--input-type=module", "--check"],
                    input=read(module), capture_output=True, text=True,
                )
                if proc.returncode != 0:
                    detail = proc.stderr.strip().splitlines()
                    fail(module, "does not parse as an ES module:\n      "
                                 + "\n      ".join(detail[:4]))

        # An inline handler needs 'unsafe-inline' exactly as an inline script
        # does, so it is dead on arrival for the same reason.
        for match in re.finditer(r"\son(?:click|change|submit|input|load)=", html):
            line = html[: match.start()].count("\n") + 1
            fail(page, f"line {line}: inline event handler — blocked by the "
                       "console CSP; use addEventListener in the module")


# ---------------- 3: every id a module asks for, its page defines ---------


def check_element_ids() -> None:
    for page in PAGES:
        html = read(page)
        declared = set(re.findall(r'\bid="([^"]+)"', html))

        for src in re.findall(r'<script\b[^>]*\bsrc="([^"]+)"', html):
            module = os.path.normpath(os.path.join(os.path.dirname(page), src))
            if not os.path.isfile(module):
                continue
            code = read(module)
            wanted = set(re.findall(r'\$\(\s*"([^"]+)"\s*\)', code))
            wanted |= set(re.findall(r'getElementById\(\s*"([^"]+)"\s*\)', code))
            for missing in sorted(wanted - declared):
                fail(module, f'asks for #{missing}, which '
                             f'{os.path.basename(os.path.dirname(page))}/'
                             f'{os.path.basename(page)} does not define')


# ---------------- 4: deploy placeholders are still placeholders -----------


def check_placeholders(policies: dict[str, str]) -> None:
    """The value itself must still be the token, not a hostname it stands for.

    Counting occurrences would be fooled by the prose around them — both files
    name these tokens in comments — so each is checked where it is read.
    """
    config = os.path.join(CONSOLE, "config.js")
    value = re.search(r'API_BASE_URL\s*=\s*"([^"]*)"', read(config))
    if not value:
        fail(config, "no API_BASE_URL export to check")
    elif value.group(1) != "__API_BASE_URL__":
        fail(config, f"API_BASE_URL is {value.group(1)!r}, not the placeholder "
                     "— deploy-console.sh substitutes it and restores it "
                     "afterwards; a live hostname must never be committed")

    path = os.path.join(HOSTING, "firebase.json")
    for source, policy in policies.items():
        if "__API_ORIGIN__" not in policy:
            fail(path, f"the {source} CSP no longer carries __API_ORIGIN__ — "
                       "either it was substituted and not restored, or the "
                       "policy stopped naming it and the consoles can no "
                       "longer reach the API")
        if "frame-src 'self'" not in policy:
            fail(path, f"the {source} CSP's frame-src is not 'self' — auth.js "
                       "sets authDomain to the page's own host, so the SDK's "
                       "auth iframe is same-origin and sign-in cannot complete "
                       "without it")


# ---------------- 5 & 6: hosting rewrites and the two console CSPs --------


def check_hosting() -> dict[str, str]:
    path = os.path.join(HOSTING, "firebase.json")
    config = json.loads(read(path))
    hosting = config["hosting"]

    for rule in hosting.get("rewrites", []):
        destination = rule.get("destination")
        if not destination or destination.startswith("http"):
            continue
        target = os.path.join(PUBLIC, destination.lstrip("/"))
        if not os.path.isfile(target):
            fail(path, f'rewrite {rule["source"]} → {destination}, which does '
                       f"not exist under public/")

    policies = {}
    for block in hosting.get("headers", []):
        for header in block.get("headers", []):
            if header["key"] == "Content-Security-Policy":
                policies[block["source"]] = header["value"]

    console, aliases = policies.get("/console/**"), policies.get("{/login,/account}")
    if console is None or aliases is None:
        fail(path, "one of the two console CSP entries is missing")
    elif console != aliases:
        fail(path, "the /console/** and {/login,/account} CSPs have drifted; "
                   "a Hosting header matches the request path, so the aliases "
                   "would be served a different policy than the pages they "
                   "rewrite to")

    # The console pages resolve their stylesheet, module script and onward
    # links through <base href="/console/…">, so the same page works at its
    # rewritten address (/login, /account). A CSP `base-uri 'none'` makes the
    # browser drop that element — the page then loads no CSS and no script,
    # and "Sign in" does nothing. First production deploy shipped exactly
    # that. `'self'` keeps the injected-off-site-base defence.
    if console is not None:
        directive = re.search(r"base-uri\s+([^;]+)", console)
        allowed = directive.group(1).split() if directive else []
        pages_with_base = [
            os.path.relpath(page, HOSTING)
            for page in glob.glob(os.path.join(PUBLIC, "console", "**", "*.html"), recursive=True)
            if "<base " in read(page)
        ]
        if pages_with_base and "'self'" not in allowed:
            fail(path, "the console CSP's base-uri is "
                       f"{' '.join(allowed) or 'unset'!r}, but these pages rely "
                       f"on <base>: {', '.join(pages_with_base)} — the browser "
                       "blocks the element, every relative asset 404s and the "
                       "sign-in button is dead")

    return {
        source: value
        for source, value in policies.items()
        if source in ("/console/**", "{/login,/account}")
    }


# ---------------- 7: every path a console calls is on the gateway ---------


def gateway_paths() -> list[list[str]]:
    """Declared paths, as segment lists. Read by regex to avoid a PyYAML dep."""
    body = read(GATEWAY)
    paths = body.split("\npaths:\n", 1)[1] if "\npaths:\n" in body else ""
    return [
        line.strip().rstrip(":").split("/")[1:]
        for line in re.findall(r"^  (/\S*):\s*$", paths, re.M)
    ]


_HOLE = "\x00"


def console_paths(code: str) -> set[str]:
    """`/v1/...` paths passed to api()/apiBlob(), with `${…}` marked as holes.

    A single-assignment `const name = ` + "`/v1/…`" + ` is expanded first, so a
    call built on a base variable is checked rather than skipped.
    """
    bases = dict(re.findall(r"const\s+(\w+)\s*=\s*`(/v1/[^`]*)`", code))
    found = set()
    for raw in re.findall(r"\bapi(?:Blob)?\(\s*[`\"']([^`\"']*)[`\"']", code):
        for name, value in bases.items():
            raw = raw.replace("${" + name + "}", value)
        if not raw.startswith("/v1/"):
            continue
        found.add(re.sub(r"\$\{[^}]*\}", _HOLE, raw.split("?", 1)[0]))
    return found


def segment_matches(called: str, declared: str) -> bool:
    if declared.startswith("{") and declared.endswith("}"):
        return True
    if called == _HOLE:
        return True
    # A hole at the end of a literal segment is a query string or a suffix the
    # page appends; the literal part still has to be the declared segment.
    return called.rstrip(_HOLE) == declared


def check_gateway() -> None:
    declared = gateway_paths()
    for page in PAGES:
        html = read(page)
        for src in re.findall(r'<script\b[^>]*\bsrc="([^"]+)"', html):
            module = os.path.normpath(os.path.join(os.path.dirname(page), src))
            if not os.path.isfile(module):
                continue
            for called in sorted(console_paths(read(module))):
                parts = called.split("/")[1:]
                if not any(
                    len(parts) == len(row)
                    and all(segment_matches(a, b) for a, b in zip(parts, row))
                    for row in declared
                ):
                    shown = called.replace(_HOLE, "${…}")
                    fail(module, f"calls {shown}, which backend/gateway/"
                                 f"openapi.yaml does not declare — ESPv2 is an "
                                 f"allowlist, so it 404s in production")


def main() -> int:
    check_scripts()
    check_element_ids()
    check_placeholders(check_hosting())
    check_gateway()

    if failures:
        print("Console checks failed:\n", file=sys.stderr)
        for line in failures:
            print(f"  - {line}", file=sys.stderr)
        return 1
    print("Console checks passed.")
    return 0


if __name__ == "__main__":
    sys.exit(main())
