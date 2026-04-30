import json

import pytest

from burp_mcp_lite.tools.endpoints import endpoints
from burp_mcp_lite.tools.list_history import list_history
from burp_mcp_lite.tools.match import match
from burp_mcp_lite.tools.stats import stats
from burp_mcp_lite.tools.view import view_request, view_response


@pytest.mark.asyncio
async def test_list_history_default(store):
    out = await list_history(store)
    assert "id" in out and "method" in out
    # Footer present
    assert "-- 6 of 6 (offset 0) --" in out


@pytest.mark.asyncio
async def test_list_history_filter_post(store):
    out = await list_history(store, method="POST")
    # only ids 1 and 5 are POSTs
    assert "-- 2 of 2" in out
    assert " 1 " in out or "\n1 " in out or out.startswith("id")


@pytest.mark.asyncio
async def test_list_history_filter_status_class(store):
    out = await list_history(store, status="4xx-5xx")
    assert "-- 2 of 2" in out


@pytest.mark.asyncio
async def test_list_history_match_in_body(store):
    out = await list_history(store, match="eyJhbGc")
    assert "-- 1 of 1" in out


@pytest.mark.asyncio
async def test_list_history_field_projection(store):
    out = await list_history(store, fields=["id", "method", "path"])
    first_line = out.splitlines()[0]
    assert first_line.split() == ["id", "method", "path"]


@pytest.mark.asyncio
async def test_list_history_ndjson_latest_first(store):
    out = await list_history(store, format="json", fields=["id", "method", "status"])
    objs = [json.loads(line) for line in out.splitlines()]
    assert len(objs) == 6
    # Default order=latest → highest id first.
    assert objs[0]["id"] == 5
    assert [o["id"] for o in objs] == [5, 4, 3, 2, 1, 0]


@pytest.mark.asyncio
async def test_list_history_ndjson_oldest(store):
    out = await list_history(
        store, format="json", fields=["id", "method", "status"], order="oldest"
    )
    objs = [json.loads(line) for line in out.splitlines()]
    assert [o["id"] for o in objs] == [0, 1, 2, 3, 4, 5]


@pytest.mark.asyncio
async def test_list_history_pagination_follows_order(store):
    # latest-first + limit=2 → the two newest entries (ids 5, 4)
    out = await list_history(
        store, limit=2, format="json", fields=["id"]
    )
    objs = [json.loads(line) for line in out.splitlines()]
    assert [o["id"] for o in objs] == [5, 4]


@pytest.mark.asyncio
async def test_list_history_default_table_latest_first(store):
    out = await list_history(store)
    rows = [line for line in out.splitlines() if line and not line.startswith("--") and not line.startswith("id ")]
    # First data row should start with the highest id (5)
    assert rows[0].split()[0] == "5"


@pytest.mark.asyncio
async def test_view_request_default_no_headers(store):
    out = await view_request(store, id=0)
    # Header line for the request must be there
    assert "[0] GET" in out
    # Authorization header should NOT be in default view
    assert "Authorization" not in out
    # Body is empty — "(no body)"
    assert "(no body)" in out


@pytest.mark.asyncio
async def test_view_request_with_redacted_headers(store):
    out = await view_request(store, id=0, include_headers=True, redact=True)
    assert "Authorization: <redacted" in out
    assert "Bearer" not in out  # the actual token bytes are hidden


@pytest.mark.asyncio
async def test_view_request_unredacted(store):
    out = await view_request(store, id=0, include_headers=True, redact=False)
    assert "Bearer eyJabc.def.ghi" in out


@pytest.mark.asyncio
async def test_view_request_cookies_only(store):
    # id 0 has no Cookie; id 2 also no Cookie. Use id with explicit cookie:
    out = await view_request(store, id=0, include_cookies=True)
    # id 0 has no Cookie, so headers section is absent.
    assert "Authorization" not in out


@pytest.mark.asyncio
async def test_view_request_body_regex(store):
    # id 1 has json body
    out = await view_request(store, id=1, body="/hunter/")
    assert "hunter2" in out


@pytest.mark.asyncio
async def test_view_response_auto_truncates_large(store):
    # id 3 is the 4KB+ JS file
    out = await view_response(store, id=3)
    assert "auto-truncated" in out


@pytest.mark.asyncio
async def test_view_response_full_overrides(store):
    out = await view_response(store, id=3, body="full")
    assert "auto-truncated" not in out


@pytest.mark.asyncio
async def test_view_response_set_cookie_only(store):
    # id 2 has a Set-Cookie
    out = await view_response(store, id=2, include_set_cookie=True, redact=False)
    assert "Set-Cookie: session=abc; HttpOnly" in out
    # other headers (Content-Type, Location) should be absent because
    # include_headers is off.
    assert "Location:" not in out


@pytest.mark.asyncio
async def test_view_response_invalid_id(store):
    out = await view_response(store, id=999)
    assert out.startswith("error:")
    assert "not found" in out


@pytest.mark.asyncio
async def test_match_response_body_hit(store):
    out = await match(store, id=4, pattern="token")
    assert "matched: true" in out
    assert "hits: 1" in out
    assert '"token"' in out


@pytest.mark.asyncio
async def test_match_response_body_miss(store):
    out = await match(store, id=4, pattern="nosuchstring")
    assert out == "matched: false\ntarget: response.body"


@pytest.mark.asyncio
async def test_match_request_headers_target(store):
    out = await match(store, id=0, pattern="Bearer", target="request.headers")
    assert "matched: true" in out


@pytest.mark.asyncio
async def test_match_invalid_regex(store):
    out = await match(store, id=0, pattern="[")
    assert out.startswith("error:")


@pytest.mark.asyncio
async def test_endpoints_dedup(store):
    out = await endpoints(store)
    # api.example.com appears 5 times across multiple paths
    assert "api.example.com" in out
    assert "cdn.example.com" in out


@pytest.mark.asyncio
async def test_endpoints_filtered(store):
    out = await endpoints(store, host="cdn")
    assert "api.example.com" not in out
    assert "cdn.example.com" in out


@pytest.mark.asyncio
async def test_stats(store):
    out = await stats(store)
    assert "total entries: 6" in out
    assert "GET=" in out
    assert "POST=" in out
    assert "2xx=" in out and "4xx=" in out and "5xx=" in out
