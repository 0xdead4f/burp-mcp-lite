"""End-to-end test: spawn the burp-mcp-lite server as a stdio child process,
talk to it as a real MCP client, and exercise every tool against the fixture.

This is the strongest correctness signal we have without a live Burp instance.
"""

from __future__ import annotations

import os
import pathlib
import sys

import pytest
from mcp import ClientSession, StdioServerParameters
from mcp.client.stdio import stdio_client


PKG_ROOT = pathlib.Path(__file__).resolve().parent.parent
SRC = PKG_ROOT / "src"
FIXTURE = pathlib.Path(__file__).resolve().parent / "fixtures" / "sample.json"


def _server_params() -> StdioServerParameters:
    env = os.environ.copy()
    env["PYTHONPATH"] = str(SRC) + os.pathsep + env.get("PYTHONPATH", "")
    return StdioServerParameters(
        command=sys.executable,
        args=["-m", "burp_mcp_lite", "--fixture", str(FIXTURE)],
        env=env,
    )


@pytest.mark.asyncio
async def test_e2e_full_workflow():
    async with stdio_client(_server_params()) as (read, write):
        async with ClientSession(read, write) as session:
            await session.initialize()

            tools = await session.list_tools()
            names = {t.name for t in tools.tools}
            assert names == {
                "list_history", "view_request", "view_response",
                "match", "endpoints", "stats",
            }

            # 1) list_history
            result = await session.call_tool("list_history", {})
            text = result.content[0].text
            assert "-- 4 of 4 (offset 0) --" in text

            # 2) filter
            result = await session.call_tool("list_history", {"method": "POST"})
            text = result.content[0].text
            assert "-- 1 of 1" in text

            # 3) view_request — defaults hide auth
            result = await session.call_tool("view_request", {"id": 0})
            text = result.content[0].text
            assert "Authorization" not in text
            assert "[0] GET" in text

            # 4) view_request with redacted headers
            result = await session.call_tool(
                "view_request", {"id": 0, "include_headers": True}
            )
            text = result.content[0].text
            assert "Authorization: <redacted" in text
            assert "Bearer eyJ" not in text

            # 5) match — predicate hit
            result = await session.call_tool(
                "match", {"id": 3, "pattern": "token"}
            )
            text = result.content[0].text
            assert "matched: true" in text

            # 6) match — predicate miss returns short answer
            result = await session.call_tool(
                "match", {"id": 3, "pattern": "nosuchsubstring"}
            )
            text = result.content[0].text
            assert text == "matched: false\ntarget: response.body"

            # 7) endpoints
            result = await session.call_tool("endpoints", {})
            text = result.content[0].text
            assert "api.example.com" in text

            # 8) stats
            result = await session.call_tool("stats", {})
            text = result.content[0].text
            assert "total entries: 4" in text
