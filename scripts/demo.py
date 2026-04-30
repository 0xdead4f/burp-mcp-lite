"""Quick visual demo against the fixture.

Prints what the LLM would actually see for a few representative tool calls.
Run: PYTHONPATH=src python3.11 scripts/demo.py
"""

from __future__ import annotations

import asyncio
import pathlib

from burp_mcp_lite.snapshot import SnapshotStore
from burp_mcp_lite.tools.endpoints import endpoints
from burp_mcp_lite.tools.list_history import list_history
from burp_mcp_lite.tools.match import match
from burp_mcp_lite.tools.stats import stats
from burp_mcp_lite.tools.view import view_request, view_response
from burp_mcp_lite.upstream import FixtureUpstream

FIXTURE = pathlib.Path(__file__).resolve().parents[1] / "tests" / "fixtures" / "sample.json"


async def main() -> None:
    upstream = FixtureUpstream(str(FIXTURE))

    async def fetch():
        return await upstream.fetch_history()

    store = SnapshotStore(fetch, ttl_seconds=999)

    def banner(s: str) -> None:
        print()
        print("=" * 8, s, "=" * 8)

    banner("list_history()")
    print(await list_history(store))

    banner("list_history(method='POST', status='4xx-5xx')")
    print(await list_history(store, method="POST", status="4xx-5xx"))

    banner("view_request(id=0)  # default — no headers")
    print(await view_request(store, id=0))

    banner("view_request(id=0, include_headers=True)  # redacted by default")
    print(await view_request(store, id=0, include_headers=True))

    banner("view_response(id=3)  # default auto body")
    print(await view_response(store, id=3))

    banner("match(id=3, pattern='token')")
    print(await match(store, id=3, pattern="token"))

    banner("match(id=3, pattern='nosuchsubstring')")
    print(await match(store, id=3, pattern="nosuchsubstring"))

    banner("endpoints()")
    print(await endpoints(store))

    banner("stats()")
    print(await stats(store))


if __name__ == "__main__":
    asyncio.run(main())
