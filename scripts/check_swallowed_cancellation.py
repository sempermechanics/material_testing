#!/usr/bin/env python3
"""Fail when a broad catch or `runCatching` around a suspend call swallows cancellation.

ADR-018 (docs/adr/ADR-018-error-convention.md, "Cancellation"): a coroutine's
`CancellationException` is never an ordinary failure. In a suspend path use
`suspendRunCatching`, never `runCatching`, or rethrow `CancellationException`
ahead of a generic catch. detekt's `SuspendFunSwallowedCancellation` would
check it but needs type resolution, which detekt 1.23.8 cannot do here
(TD-171); this is a source heuristic in its place, the way
`check_activity_scope_work.py` stands in for a lint rule (TD-291):

* **What is checked.** Every `try { } catch (e: Exception)` (or `Throwable`,
  `RuntimeException`, `IllegalStateException`: each one catches
  `CancellationException`) and every `runCatching { }` under `app/src/main/`
  whose block calls a suspend function.
* **A suspend call** is a call to a function some main source declares
  `suspend fun` (by name), to a property or to a parameter of the enclosing
  function declared with a `suspend` type, or to one of the kotlinx.coroutines
  and androidx suspend functions in `COROUTINE_CALLS`. A name that is also a
  plain `fun` somewhere counts only inside a `suspend fun` or a coroutine
  builder's block, and a `private` one only in its own file. Blocks of a nested `launch { }` / `async { }` are a new
  coroutine and are not counted as the try's own calls.
* **Handled**, so not flagged: a `catch (e: CancellationException)` ahead of
  the broad one; a broad catch whose body rethrows (`throw e`), tests
  `is CancellationException`, or calls `rethrowIfCallerCancelled()` /
  `ensureActive()`; a `runCatching` whose chain calls `getOrThrow()` or does
  one of those; and anything inside a `NonCancellable` block (ADR-018:
  cleanup that must finish).
* **Exceptions** are listed in `scripts/swallowed_cancellation_allowlist.txt`
  by file, enclosing function, construct and suspend call, each with its
  reason and a TD- or ADR- pointer. An entry that matches nothing fails, so the
  list only shrinks.

Not caught: a suspend function called by a name some non-suspend function also
has is caught (a false positive to allowlist), while a suspend call through a
function value, an `invoke` operator or a library this list does not name is
missed. Stdlib only.

Usage: python scripts/check_swallowed_cancellation.py [--root DIR]
"""
from __future__ import annotations

import argparse
import re
import sys
from pathlib import Path
from typing import NamedTuple

sys.path.insert(0, str(Path(__file__).resolve().parent))
from check_activity_scope_work import (  # noqa: E402  (a sibling script, stdlib only)
    Function,
    _enclosing_paren,
    _skip_space,
    enclosing,
    functions,
    line_of,
    matching,
    strip_code,
)

SOURCES = Path("app/src/main")
PACKAGE_ROOT = Path("app/src/main/java/com/sempermechanics/semper")
ALLOWLIST = Path("scripts/swallowed_cancellation_allowlist.txt")

#: kotlinx.coroutines (and Firebase's `Task.await`) suspend functions whose
#: names do not also name a common non-suspend call (`first`, `toList` and
#: `single` are left out for that reason).
COROUTINE_CALLS = frozenset({
    "await", "awaitAll", "awaitClose", "cancelAndJoin", "collect", "collectLatest",
    "coroutineScope", "delay", "emit", "emitAll", "join", "joinAll", "receive",
    "runInterruptible", "send", "supervisorScope", "suspendCancellableCoroutine",
    "suspendCoroutine", "withContext", "withLock", "withTimeout", "withTimeoutOrNull",
    "yield", "currentCoroutineContext",
    # androidx: WorkManager's CoroutineWorker, Credential Manager, Lifecycle.
    "setForeground", "setProgress", "createCredential", "getCredential", "clearCredentialState",
    "repeatOnLifecycle", "withStarted", "withResumed", "withCreated",
})
#: Receivers whose call of a `COROUTINE_CALLS` name blocks instead
#: (`Tasks.await(task, timeout, unit)`, Play services' blocking await).
BLOCKING = ("Tasks", "Thread")
#: Stdlib scope functions: `x.run { }` is not `SingleFlight.run`.
SCOPE_FUNCTIONS = frozenset({
    "also", "apply", "let", "run", "takeIf", "takeUnless", "use", "with", "repeat", "synchronized",
})
#: Calls whose block is a coroutine, where an ambiguous name may be a suspend call.
CONTEXT_BUILDERS = (
    "launch\\w*|async|withContext|coroutineScope|supervisorScope|withTimeout|withTimeoutOrNull"
    "|flow|channelFlow|callbackFlow|produce|repeatOnLifecycle|runBlocking|liveData|withLock"
)
#: Catch types that catch `CancellationException` (an `IllegalStateException`).
BROAD = frozenset({"Exception", "Throwable", "RuntimeException", "IllegalStateException"})

_ANY_FUN = re.compile(
    r"((?:\b(?:suspend|inline|private|internal|public|protected|override|operator|infix|tailrec"
    r"|open|abstract|final|actual|expect|external)\s+)*)"
    r"fun\s+(?:<[^>]*>\s*)?(?:[\w.<>?, *]+\.)?(\w+)\s*\("
)
_CONTEXT_BUILDER = re.compile(r"(?<![\w$])(?:" + CONTEXT_BUILDERS + r")\s*")
_SUSPEND_PARAM = re.compile(r"(\w+)\s*:\s*(?:@\w+\s+)?suspend\b")
_SUSPEND_PROPERTY = re.compile(
    r"((?:\b(?:private|internal|public|protected|override|open|lateinit)\s+)*)"
    r"(?:val|var)\s+(\w+)\s*:\s*(?:@\w+\s+)?\(?\s*suspend\b"
)
_CALL = re.compile(r"(?<![\w$])(\w+)\s*(?:<[^<>()]*>)?\s*[({]")
_TRY = re.compile(r"(?<![\w$])try\s*\{")
_CATCH = re.compile(
    r"\s*catch\s*\(\s*(?:@[\w.]+(?:\s*\([^()]*\))?\s*)*(\w+)\s*:\s*([\w.]+)\s*\)\s*\{"
)
_RUN_CATCHING = re.compile(r"(?<![\w$])runCatching\s*\{")
_BUILDER = re.compile(r"(?<![\w$])(?:launch\w*|async)\s*")
_NON_CANCELLABLE = re.compile(r"(?<![\w$.])NonCancellable\b")
_HANDLES = re.compile(
    r"\bis\s+(?:[\w.]+\.)?CancellationException\b|\brethrowIfCallerCancelled\b|\bensureActive\b"
)
_CHAIN_HANDLES = re.compile(_HANDLES.pattern + r"|\bgetOrThrow\b")
_POINTER = re.compile(r"\b(?:TD|ADR)-\d+\b")


class Names(NamedTuple):
    """What counts as a suspend call, by name."""
    sure: frozenset[str]        # suspend wherever called
    ambiguous: frozenset[str]   # suspend somewhere, but also a non-suspend name
    private: dict[str, frozenset[str]]  # file -> its private suspend funs


def suspend_names(codes: dict[str, str]) -> Names:
    """Sort every declared `suspend fun` name, plus `COROUTINE_CALLS`.

    A name that some main source also declares as a plain `fun`, or that is a
    stdlib scope function (`run`, `apply`), is ambiguous: it counts only where
    a suspend call can be made (`in_suspend_context`). A `private suspend fun`
    counts only in its own file.
    """
    suspend: set[str] = set(COROUTINE_CALLS)
    plain: set[str] = set(SCOPE_FUNCTIONS)
    private: dict[str, frozenset[str]] = {}
    for rel, code in codes.items():
        own: set[str] = set()
        for match in _ANY_FUN.finditer(code):
            modifiers, name = match.group(1), match.group(2)
            if not re.search(r"\bsuspend\b", modifiers):
                plain.add(name)
            elif re.search(r"\bprivate\b", modifiers):
                own.add(name)
            else:
                suspend.add(name)
        # `internal var finish: suspend (Context) -> Outcome`, a test seam.
        for match in _SUSPEND_PROPERTY.finditer(code):
            (own if re.search(r"\bprivate\b", match.group(1)) else suspend).add(match.group(2))
        private[rel] = frozenset(own)
    return Names(frozenset(suspend - plain), frozenset(suspend & plain), private)


def in_suspend_context(code: str, funs: list[Function], pos: int) -> bool:
    """Whether `pos` sits where a suspend call compiles: in a `suspend fun`, or
    in a coroutine builder's block inside the innermost function."""
    inside = [f for f in funs if f.body <= pos <= f.end]
    innermost = max(inside, key=lambda f: f.start) if inside else None
    floor = innermost.body if innermost else 0
    if innermost:
        header = code[code.rfind("\n", 0, innermost.start) + 1:innermost.start]
        if re.search(r"\bsuspend\b", header):
            return True
    for match in _CONTEXT_BUILDER.finditer(code, floor, pos):
        block = _lambda_after(code, match.end())
        if block and block[0] <= pos <= block[1]:
            return True
    return False


def _lambda_after(code: str, pos: int) -> tuple[int, int] | None:
    """The `{ }` that starts at `pos`, after an optional `( )` argument list."""
    pos = _skip_space(code, pos)
    if pos < len(code) and code[pos] == "(":
        close = matching(code, pos)
        if close < 0:
            return None
        pos = _skip_space(code, close + 1)
    if pos < len(code) and code[pos] == "{":
        end = matching(code, pos)
        if end > pos:
            return pos, end
    return None


def non_cancellable(code: str) -> list[tuple[int, int]]:
    """Blocks run under `NonCancellable`: `withContext(NonCancellable) { }`,
    `withContext(Dispatchers.IO + NonCancellable) { }`, `launch(NonCancellable) { }`."""
    regions = []
    for match in _NON_CANCELLABLE.finditer(code):
        paren = _enclosing_paren(code, match.start())
        if paren < 0:
            continue
        block = _lambda_after(code, paren)
        if block:
            regions.append(block)
    return regions


def builders(code: str, start: int, end: int) -> list[tuple[int, int]]:
    """Blocks of the `launch { }` / `async { }` calls between `start` and `end`."""
    found = []
    for match in _BUILDER.finditer(code, start, end):
        block = _lambda_after(code, match.end())
        if block and block[1] <= end:
            found.append(block)
    return found


def first_suspend_call(
    code: str, start: int, end: int, sure: frozenset[str], ambiguous: frozenset[str] = frozenset()
) -> tuple[int, str] | None:
    """(offset, name) of the first suspend call between `start` and `end`, not
    counting the bodies of nested coroutine builders. An `ambiguous` name
    counts, except in a scope function's form (`run { }`, `.apply { }`)."""
    skip = builders(code, start, end)
    for match in _CALL.finditer(code, start, end):
        name = match.group(1)
        if name not in sure and name not in ambiguous:
            continue
        if name not in sure and name in SCOPE_FUNCTIONS and code[match.end() - 1] == "{":
            continue
        if any(a <= match.start() <= b for a, b in skip):
            continue
        before = code[max(0, match.start() - 16):match.start()]
        if re.search(r"(?:\bfun|(?<![\w$])(?:" + "|".join(BLOCKING) + r")\s*\.)\s*$", before):
            continue
        return match.start(), name
    return None


class Finding(NamedTuple):
    path: str
    function: str   # the function holding the construct
    construct: str  # `runCatching` or `catch Exception` (the caught type)
    call: str       # the first suspend call in its block
    line: int       # the construct's line
    call_line: int


def _suspend_params(code: str, funs: list[Function], pos: int) -> set[str]:
    """Names of `suspend`-typed parameters of every function around `pos`."""
    names: set[str] = set()
    for fun in funs:
        if fun.start <= pos <= fun.end:
            names.update(m.group(1) for m in _SUSPEND_PARAM.finditer(code, *fun.params))
    return names


def _catches(code: str, after_try: int) -> tuple[list[tuple[str, str, int, int, int]], int]:
    """The catch clauses after a try block: (variable, type, start, body start,
    body end) each, and where the last one ends."""
    clauses = []
    pos = after_try
    while True:
        match = _CATCH.match(code, pos)
        if not match:
            break
        brace = match.end() - 1
        end = matching(code, brace)
        if end < 0:
            break
        clauses.append((match.group(1), match.group(2), code.index("catch", match.start()), brace + 1, end))
        pos = end + 1
    return clauses, pos


def _rethrows(body: str, variable: str) -> bool:
    if _HANDLES.search(body):
        return True
    return re.search(r"\bthrow\s+" + re.escape(variable) + r"\b(?!\s*[.(])", body) is not None


def _chain(code: str, pos: int) -> str:
    """The `.call { } .call( )` chain that follows a `runCatching { }` ending at `pos`."""
    start = pos
    while True:
        nxt = _skip_space(code, pos)
        if nxt + 1 < len(code) and code[nxt] in "?." and code[nxt:nxt + 2] != "?:":
            match = re.compile(r"\??\.\s*\w+\s*").match(code, nxt)
            if not match:
                break
            pos = match.end()
            while pos < len(code) and code[pos] in "({":
                close = matching(code, pos)
                if close < 0:
                    return code[start:]
                pos = _skip_space(code, close + 1)
        else:
            break
    return code[start:pos]


def check_file(code: str, rel: str, names: Names) -> list[Finding]:
    """Findings in one stripped source file."""
    funs = functions(code)
    safe = non_cancellable(code)
    findings: list[Finding] = []

    def suspends(start: int, end: int) -> tuple[int, str] | None:
        sure = names.sure | names.private.get(rel, frozenset()) | _suspend_params(code, funs, start)
        ambiguous = names.ambiguous if in_suspend_context(code, funs, start) else frozenset()
        return first_suspend_call(code, start, end, frozenset(sure), ambiguous)

    def add(at: int, construct: str, hit: tuple[int, str]) -> None:
        if any(a <= at <= b for a, b in safe):
            return
        findings.append(Finding(
            rel, enclosing(code, funs, at), construct, hit[1], line_of(code, at), line_of(code, hit[0]),
        ))

    for match in _TRY.finditer(code):
        brace = match.end() - 1
        end = matching(code, brace)
        if end < 0:
            continue
        clauses, _ = _catches(code, end + 1)
        cancellation_first = False
        for variable, caught, start, body, body_end in clauses:
            simple = caught.split(".")[-1]
            if simple == "CancellationException":
                cancellation_first = True
                continue
            if simple not in BROAD or cancellation_first:
                continue
            if _rethrows(code[body:body_end], variable):
                continue
            hit = suspends(brace + 1, end)
            if hit:
                add(start, f"catch {simple}", hit)
            break  # a later broad clause catches nothing the first did not

    for match in _RUN_CATCHING.finditer(code):
        brace = match.end() - 1
        end = matching(code, brace)
        if end < 0:
            continue
        hit = suspends(brace + 1, end)
        if hit and not _CHAIN_HANDLES.search(_chain(code, end + 1)):
            add(match.start(), "runCatching", hit)
    return sorted(findings, key=lambda f: f.line)


class Entry(NamedTuple):
    path: str
    function: str
    construct: str
    call: str
    reason: str
    line: int


def read_allowlist(path: Path) -> tuple[list[Entry], list[str]]:
    entries: list[Entry] = []
    problems: list[str] = []
    if not path.is_file():
        return entries, problems
    keys: set[tuple[str, str, str, str]] = set()
    for number, raw in enumerate(path.read_text(encoding="utf-8").splitlines(), 1):
        line = raw.strip()
        if not line or line.startswith("#"):
            continue
        parts = [part.strip() for part in line.split("|", 4)]
        where = f"{ALLOWLIST}:{number}"
        if len(parts) != 5 or not all(parts):
            problems.append(
                f"{where}: expected `path | function | construct | call | reason`, got {line!r}"
            )
            continue
        entry = Entry(*parts, number)
        if not _POINTER.search(entry.reason):
            problems.append(f"{where}: the reason names no TD- or ADR- pointer")
        key = entry[:4]
        if key in keys:
            problems.append(f"{where}: {' | '.join(key)} is listed twice")
        keys.add(key)
        entries.append(entry)
    return entries, problems


def _rel(source: Path, root: Path) -> str:
    package_root = root / PACKAGE_ROOT
    try:
        return source.relative_to(package_root).as_posix()
    except ValueError:
        return source.relative_to(root).as_posix()


def check(root: Path) -> tuple[list[str], int, int]:
    """Failures, files read, and findings the allowlist covers."""
    entries, failures = read_allowlist(root / ALLOWLIST)
    allowed = {entry[:4]: entry for entry in entries}
    used: set[tuple[str, str, str, str]] = set()
    covered = 0
    sources = sorted((root / SOURCES).rglob("*.kt"))
    codes = {_rel(source, root): strip_code(source.read_text(encoding="utf-8")) for source in sources}
    names = suspend_names(codes)
    for rel, code in codes.items():
        for finding in check_file(code, rel, names):
            key = finding[:4]
            if key in allowed:
                used.add(key)
                covered += 1
                continue
            failures.append(
                f"{rel}:{finding.line}: `{finding.construct}` around the suspend call "
                f"`{finding.call}` (:{finding.call_line}, in `{finding.function}`) swallows "
                "CancellationException"
            )
    for entry in entries:
        if entry[:4] not in used:
            failures.append(
                f"{ALLOWLIST}:{entry.line}: {' | '.join(entry[:4])} matches nothing; remove its entry"
            )
    return failures, len(sources), covered


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    parser.add_argument("--root", default=".", help="repository root (default: cwd)")
    args = parser.parse_args()
    root = Path(args.root).resolve()

    failures, checked, covered = check(root)
    print(f"checked {checked} Kotlin files under {SOURCES}; {covered} allowlisted finding(s)")
    if failures:
        print("\nADR-018, cancellation turned into an ordinary failure:\n", file=sys.stderr)
        for failure in failures:
            print(f"  {failure}", file=sys.stderr)
        print(
            "\nUse suspendRunCatching (util/SuspendRunCatching.kt) instead of runCatching, put "
            "`catch (e: CancellationException) { throw e }` ahead of the broad catch, or call "
            "rethrowIfCallerCancelled() first in it (util/CallerCancellation.kt). A scope that "
            "is never cancelled, or a name that is not a suspend call here, goes in "
            "scripts/swallowed_cancellation_allowlist.txt with its reason and a TD- or ADR- pointer.",
            file=sys.stderr,
        )
        return 1
    print("no broad catch or runCatching around a suspend call swallows cancellation outside the allowlist")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
