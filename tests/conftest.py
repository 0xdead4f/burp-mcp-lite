"""Shared fixtures: a ready-to-go in-memory SnapshotStore with sample data.

Sample is constructed so each tool has something interesting to find.
"""

from __future__ import annotations

import pytest

from burp_mcp_lite.snapshot import SnapshotStore


def _req(method: str, host: str, path: str, *, headers: list[tuple[str, str]] | None = None, body: str = "") -> str:
    head = [f"{method} {path} HTTP/1.1", f"Host: {host}"]
    for k, v in headers or []:
        head.append(f"{k}: {v}")
    return "\r\n".join(head) + "\r\n\r\n" + body


def _resp(status: int, *, ct: str = "application/json", headers: list[tuple[str, str]] | None = None, body: str = "") -> str:
    head = [f"HTTP/1.1 {status} OK", f"Content-Type: {ct}"]
    for k, v in headers or []:
        head.append(f"{k}: {v}")
    return "\r\n".join(head) + "\r\n\r\n" + body


SAMPLE: list[tuple[str, str, str | None]] = [
    # 0 — auth GET
    (
        _req("GET", "api.example.com", "/v1/users?id=42",
             headers=[("Authorization", "Bearer eyJabc.def.ghi"), ("Accept", "application/json")]),
        _resp(200, body='{"id":42,"name":"alice","email":"alice@example.com"}'),
        None,
    ),
    # 1 — failed login
    (
        _req("POST", "api.example.com", "/v1/login",
             headers=[("Content-Type", "application/json")],
             body='{"user":"alice","pw":"hunter2"}'),
        _resp(401, body='{"error":"invalid_credentials"}'),
        None,
    ),
    # 2 — admin redirect
    (
        _req("GET", "api.example.com", "/admin"),
        _resp(302, headers=[("Location", "/login"), ("Set-Cookie", "session=abc; HttpOnly")], body=""),
        None,
    ),
    # 3 — large static file (just over the auto-trunc threshold)
    (
        _req("GET", "cdn.example.com", "/static/app.js"),
        _resp(200, ct="application/javascript", body=("// line\n" * 600)),
        None,
    ),
    # 4 — body containing a token to find
    (
        _req("GET", "api.example.com", "/v1/profile",
             headers=[("Authorization", "Bearer t.t.t")]),
        _resp(200, body='{"token":"eyJhbGciOiJIUzI1NiJ9.payload.sig","ok":true}'),
        None,
    ),
    # 5 — POST 500
    (
        _req("POST", "api.example.com", "/v1/checkout",
             body='{"item":"x","qty":1}'),
        _resp(500, body='{"error":"internal"}'),
        None,
    ),
]


@pytest.fixture
def store() -> SnapshotStore:
    async def fetch(offset: int = 0):
        return list(SAMPLE[offset:])

    s = SnapshotStore(fetch, ttl_seconds=999)
    return s
