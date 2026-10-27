"""TD-230: routers answer requests and call services for multi-step flows;
`deps.py` holds identity and device attestation, `step_up.py` the step-up
tiers on top of them."""
import ast
import pathlib

APP = pathlib.Path(__file__).resolve().parents[1] / "app"

#: A handler's code, docstring excluded, past which its steps belong in
#: `app/services/` (the row's "about 40 lines").
MAX_ROUTER_FUNCTION_LINES = 40

#: Functions that build routes rather than answer one; their nested handlers
#: are measured on their own.
ROUTE_FACTORIES = {"roster.py:roster_router"}


def _code_lines(fn: ast.FunctionDef | ast.AsyncFunctionDef) -> int:
    first = fn.body[0]
    if (isinstance(first, ast.Expr) and isinstance(first.value, ast.Constant)
            and isinstance(first.value.value, str)):
        if len(fn.body) == 1:
            return 0
        first = fn.body[1]
    return fn.end_lineno - first.lineno + 1


def test_no_router_function_holds_a_long_flow():
    long = []
    for path in sorted((APP / "routers").glob("*.py")):
        for node in ast.walk(ast.parse(path.read_text(encoding="utf-8"))):
            if not isinstance(node, ast.FunctionDef | ast.AsyncFunctionDef):
                continue
            name = f"{path.name}:{node.name}"
            if name not in ROUTE_FACTORIES and _code_lines(node) > MAX_ROUTER_FUNCTION_LINES:
                long.append(f"{name} ({_code_lines(node)} lines)")
    assert long == []


def test_deps_stays_under_500_lines():
    assert len((APP / "deps.py").read_text(encoding="utf-8").splitlines()) < 500


def test_step_up_builds_on_deps_and_not_the_reverse():
    def imports(path: pathlib.Path) -> set[str]:
        return {
            node.module or "" for node in ast.walk(ast.parse(path.read_text(encoding="utf-8")))
            if isinstance(node, ast.ImportFrom) and node.level == 1
        } | {
            alias.name for node in ast.walk(ast.parse(path.read_text(encoding="utf-8")))
            if isinstance(node, ast.ImportFrom) and node.level == 1 and not node.module
            for alias in node.names
        }

    assert "deps" in imports(APP / "step_up.py")
    assert "step_up" not in imports(APP / "deps.py")


def test_services_do_not_import_routers():
    """Services sit under the routers: a router calls a service, never the
    reverse, so the order routers load in cannot matter (TD-86)."""
    offenders = []
    for path in sorted((APP / "services").glob("*.py")):
        for node in ast.walk(ast.parse(path.read_text(encoding="utf-8"))):
            if isinstance(node, ast.ImportFrom) and (node.module or "").startswith("routers"):
                offenders.append(f"{path.name}:{node.lineno}")
    assert offenders == []
