"""The structured access-log middleware runs on every request and emits one
JSON line with latencyMs/outcome (replacing the old plain-text logging)."""
import json


async def test_request_id_header_present(client):
    r = await client.get("/healthz")
    assert r.status_code == 200
    assert r.headers.get("X-Request-Id")


async def test_access_log_line_is_structured_json(client, caplog):
    import logging

    with caplog.at_level(logging.INFO, logger="indic.access"):
        await client.get("/healthz")
    lines = [rec.message for rec in caplog.records if rec.name == "indic.access"]
    assert lines, "no access-log line emitted"
    entry = json.loads(lines[-1])
    assert entry["method"] == "GET"
    assert entry["path"] == "/healthz"
    assert entry["status"] == 200
    assert entry["outcome"] == "ok"
    assert "latencyMs" in entry and "requestId" in entry
