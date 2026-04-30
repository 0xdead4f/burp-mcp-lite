"""CLI entry point: `python -m burp_mcp_lite`.

Modes:
  default        — connect to Burp's MCP at http://127.0.0.1:9876/
  --fixture FILE — read history from a JSON file (offline / dev / smoke test)
  --url URL      — override the upstream URL
  --ttl SECONDS  — snapshot TTL before auto-refresh (default 30s)
"""

from __future__ import annotations

import argparse
import asyncio
import logging
import sys

from .server import serve_stdio
from .snapshot import SnapshotStore
from .upstream import BurpUpstream, FixtureUpstream


def _build_store(args: argparse.Namespace) -> SnapshotStore:
    if args.fixture:
        upstream = FixtureUpstream(args.fixture)
    else:
        upstream = BurpUpstream(url=args.url, page_size=args.page_size, max_entries=args.max_entries)
    return SnapshotStore(upstream.fetch, ttl_seconds=args.ttl)


def _parse_argv(argv: list[str]) -> argparse.Namespace:
    p = argparse.ArgumentParser(prog="burp-mcp-lite")
    p.add_argument("--url", default="http://127.0.0.1:9876/",
                   help="Burp MCP SSE URL (default: %(default)s).")
    p.add_argument("--fixture", default=None,
                   help="Read history from a JSON fixture file (no Burp needed).")
    p.add_argument("--ttl", type=float, default=30.0,
                   help="Snapshot cache TTL in seconds (default: 30).")
    p.add_argument("--page-size", type=int, default=20,
                   help="Per-call page size when fetching from Burp (default: 20).")
    p.add_argument("--max-entries", type=int, default=5000,
                   help="Cap on history entries pulled per refresh (default: 5000).")
    p.add_argument("--verbose", "-v", action="store_true", help="Log to stderr.")
    return p.parse_args(argv)


def main(argv: list[str] | None = None) -> int:
    args = _parse_argv(list(argv) if argv is not None else sys.argv[1:])
    logging.basicConfig(
        level=logging.INFO if args.verbose else logging.WARNING,
        format="%(asctime)s %(levelname)s %(name)s %(message)s",
        stream=sys.stderr,
    )
    store = _build_store(args)
    asyncio.run(serve_stdio(store))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
