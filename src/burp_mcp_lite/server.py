"""MCP stdio server. Registers the six tools and routes calls into the
tool implementations.

Tool descriptions are kept compact — they're paid for in the LLM's tool-schema
budget on every turn.
"""

from __future__ import annotations

import logging
from typing import Any

import mcp.types as t
from mcp.server.lowlevel import Server
from mcp.server.stdio import stdio_server

from .snapshot import SnapshotStore
from .tools.endpoints import endpoints as tool_endpoints
from .tools.list_history import list_history as tool_list_history
from .tools.match import match as tool_match
from .tools.stats import stats as tool_stats
from .tools.view import view_request as tool_view_request
from .tools.view import view_response as tool_view_response

log = logging.getLogger(__name__)


def _tools() -> list[t.Tool]:
    return [
        t.Tool(
            name="list_history",
            description=(
                "Browse Burp proxy history with field projection and filters. "
                "Returns a compact text table by default. Use this for Recon "
                "(what was captured?), Filter (only POSTs that 4xx'd), and "
                "Cross-search (regex over response bodies via match=)."
            ),
            inputSchema={
                "type": "object",
                "properties": {
                    "limit": {"type": "integer", "default": 20, "minimum": 0},
                    "offset": {"type": "integer", "default": 0, "minimum": 0},
                    "fields": {
                        "type": "array",
                        "items": {
                            "type": "string",
                            "enum": ["id", "method", "status", "host", "path", "len", "mime", "time"],
                        },
                        "description": "Columns to render. Default: id,method,status,host,path,len.",
                    },
                    "host": {"type": "string", "description": "Substring match on host."},
                    "path": {"type": "string", "description": "Regex on request path."},
                    "method": {
                        "anyOf": [
                            {"type": "string"},
                            {"type": "array", "items": {"type": "string"}},
                        ],
                        "description": "HTTP method, or list. Comma-separated string also accepted.",
                    },
                    "status": {
                        "type": "string",
                        "description": 'Status filter. Examples: "200", "4xx", "200,201,204", "400-499", "4xx-5xx".',
                    },
                    "mime": {"type": "string", "description": "Substring on response Content-Type."},
                    "match": {"type": "string", "description": "Regex; combined with match_in to filter rows."},
                    "match_in": {
                        "type": "string",
                        "default": "response.body",
                        "enum": [
                            "request.body", "request.headers", "request.all",
                            "response.body", "response.headers", "response.all",
                        ],
                    },
                    "order": {
                        "type": "string",
                        "enum": ["latest", "oldest"],
                        "default": "latest",
                        "description": "Row ordering. latest=newest first (ids descending). Pagination follows this order.",
                    },
                    "format": {"type": "string", "enum": ["text", "json"], "default": "text"},
                    "refresh": {"type": "boolean", "default": False, "description": "Force re-fetch from Burp."},
                },
            },
        ),
        t.Tool(
            name="view_request",
            description=(
                "View one request by id (from list_history). Headers and "
                "cookies are OFF by default — pass include_headers=true if you "
                "need them. Auth header values are redacted by default; pass "
                'redact=false for the raw bytes. Body slicing: "full", "none", '
                '"head:N", "tail:N", or "/regex/".'
            ),
            inputSchema={
                "type": "object",
                "required": ["id"],
                "properties": {
                    "id": {"type": "integer"},
                    "include_headers": {"type": "boolean", "default": False},
                    "include_cookies": {"type": "boolean", "default": False},
                    "redact": {"type": "boolean", "default": True},
                    "body": {"type": "string", "default": "full"},
                    "context": {"type": "integer", "default": 1},
                },
            },
        ),
        t.Tool(
            name="view_response",
            description=(
                "View one response by id. Same toggles as view_request. Default "
                'body="auto" prints full body if <4KB, else head:20 + a marker. '
                'Pass body="full" to override.'
            ),
            inputSchema={
                "type": "object",
                "required": ["id"],
                "properties": {
                    "id": {"type": "integer"},
                    "include_headers": {"type": "boolean", "default": False},
                    "include_set_cookie": {"type": "boolean", "default": False},
                    "redact": {"type": "boolean", "default": True},
                    "body": {"type": "string", "default": "auto"},
                    "context": {"type": "integer", "default": 1},
                },
            },
        ),
        t.Tool(
            name="match",
            description=(
                "Predicate: does the entry match a regex? Returns "
                '"matched: true/false" plus a small evidence snippet — never '
                "the full body. Use this when you want to verify presence of "
                "a token/value without dumping the response into context."
            ),
            inputSchema={
                "type": "object",
                "required": ["id", "pattern"],
                "properties": {
                    "id": {"type": "integer"},
                    "pattern": {"type": "string"},
                    "target": {
                        "type": "string",
                        "default": "response.body",
                        "enum": [
                            "request.body", "request.headers", "request.all",
                            "response.body", "response.headers", "response.all",
                        ],
                    },
                    "case_sensitive": {"type": "boolean", "default": False},
                    "context": {"type": "integer", "default": 0},
                    "max_hits": {"type": "integer", "default": 10},
                },
            },
        ),
        t.Tool(
            name="endpoints",
            description=(
                "Deduplicated method+host+path inventory across history, with "
                "hit counts. Query strings are stripped for dedup. Filters: "
                "host, path, method."
            ),
            inputSchema={
                "type": "object",
                "properties": {
                    "host": {"type": "string"},
                    "path": {"type": "string"},
                    "method": {
                        "anyOf": [
                            {"type": "string"},
                            {"type": "array", "items": {"type": "string"}},
                        ],
                    },
                },
            },
        ),
        t.Tool(
            name="stats",
            description="Counts by method, status class, and top hosts across the cached snapshot.",
            inputSchema={"type": "object", "properties": {}},
        ),
    ]


def build_server(store: SnapshotStore, name: str = "burp-mcp-lite") -> Server:
    server: Server = Server(
        name=name,
        instructions=(
            "Token-efficient Burp proxy history viewer. Default outputs are "
            "headerless and redacted — opt in when you need raw bytes."
        ),
    )

    @server.list_tools()
    async def _list_tools() -> list[t.Tool]:
        return _tools()

    @server.call_tool()
    async def _call_tool(name: str, args: dict[str, Any]) -> list[t.TextContent]:
        try:
            text = await _dispatch(store, name, args or {})
        except Exception as exc:  # pragma: no cover — surfaced to LLM
            log.exception("tool %s failed", name)
            text = f"error: {type(exc).__name__}: {exc}"
        return [t.TextContent(type="text", text=text)]

    return server


async def _dispatch(store: SnapshotStore, name: str, args: dict[str, Any]) -> str:
    if name == "list_history":
        return await tool_list_history(store, **args)
    if name == "view_request":
        return await tool_view_request(store, **args)
    if name == "view_response":
        return await tool_view_response(store, **args)
    if name == "match":
        return await tool_match(store, **args)
    if name == "endpoints":
        return await tool_endpoints(store, **args)
    if name == "stats":
        return await tool_stats(store)
    return f"error: unknown tool {name!r}"


async def serve_stdio(store: SnapshotStore) -> None:
    server = build_server(store)
    async with stdio_server() as (read, write):
        await server.run(read, write, server.create_initialization_options())
