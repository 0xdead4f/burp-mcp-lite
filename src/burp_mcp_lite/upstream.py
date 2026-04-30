"""SSE client to Burp's official MCP server.

Burp exposes the proxy history through the `get_proxy_http_history` tool,
returning paginated results. Each result item is a JSON string of
`{"request": "...", "response": "...", "notes": "..."}`.

We reconnect lazily — there's no benefit to keeping the SSE pipe open between
infrequent refreshes. Each `fetch(offset)` call opens one session, pages
through Burp's history starting at `offset`, and closes the session.
"""

from __future__ import annotations

import json
import logging
from contextlib import AsyncExitStack
from typing import Optional

from mcp import ClientSession
from mcp.client.sse import sse_client

from .snapshot import RawEntry

log = logging.getLogger(__name__)

# A single "page" we ask Burp for. Empirically 20 is reliable on real Burp
# histories; larger pages (e.g. 200) cause the official server to close the
# SSE pipe mid-fetch on entries with very long Cookie headers.
DEFAULT_PAGE = 20


class BurpUpstream:
    """Stateless client. Burp's MCP server runs on 127.0.0.1:9876 by default
    and is bound to localhost; no auth needed.
    """

    def __init__(
        self,
        url: str = "http://127.0.0.1:9876/",
        page_size: int = DEFAULT_PAGE,
        max_entries: int = 5000,
    ) -> None:
        self.url = url
        self.page_size = page_size
        self.max_entries = max_entries

    async def fetch(self, offset: int = 0) -> list[RawEntry]:
        """Fetch the slice of proxy history starting at `offset`, capped at
        `self.max_entries`. Pages internally so a single call can return more
        than `page_size` entries without the caller having to re-loop.
        """
        out: list[RawEntry] = []
        cap = self.max_entries
        async with AsyncExitStack() as stack:
            read, write = await stack.enter_async_context(sse_client(self.url))
            session: ClientSession = await stack.enter_async_context(
                ClientSession(read, write)
            )
            await session.initialize()

            cur_offset = offset
            while len(out) < cap:
                count = min(self.page_size, cap - len(out))
                result = await session.call_tool(
                    "get_proxy_http_history",
                    {"count": count, "offset": cur_offset},
                )
                page = _decode_history_result(result)
                if not page:
                    break
                out.extend(page)
                if len(page) < count:
                    break
                cur_offset += len(page)
        return out


TRUNCATION_MARKER = "... (truncated)"


def _decode_history_result(result) -> list[RawEntry]:
    """Decode the upstream `CallToolResult`.

    The official server's `mcpPaginatedTool` joins all paginated entries
    (each a JSON-encoded `{"request":...,"response":...,"notes":...}`) into a
    single TextContent separated by `\\n\\n`. When the offset is past the end,
    it emits the literal string "Reached end of items".

    Catch: the upstream applies a blunt 5000-char cap per entry
    (`truncateIfNeeded` in Burp's `Tools.kt`) and appends "... (truncated)" to
    the chopped JSON. That makes the JSON unparseable. We salvage what we can
    from the partial string with a tolerant extractor.
    """
    entries: list[RawEntry] = []
    contents = getattr(result, "content", None) or []
    for c in contents:
        text = getattr(c, "text", None)
        if not text:
            continue
        if text == "Reached end of items":
            continue
        if text.startswith("HTTP history access denied"):
            raise UpstreamDenied(text)
        for piece in text.split("\n\n"):
            piece = piece.strip()
            if not piece:
                continue
            entry = _decode_one(piece)
            if entry is not None:
                entries.append(entry)
    return entries


def _decode_one(piece: str) -> Optional[RawEntry]:
    """Parse one entry. Falls back to a tolerant salvage on truncated JSON."""
    try:
        obj = json.loads(piece)
        return (
            obj.get("request") or "",
            obj.get("response") or "",
            obj.get("notes"),
        )
    except json.JSONDecodeError:
        pass

    # Salvage path: drop the truncation marker, then pull the request and
    # response string values out by manual scan.
    body = piece
    if body.endswith(TRUNCATION_MARKER):
        body = body[: -len(TRUNCATION_MARKER)]

    req = _extract_json_string(body, '"request":"')
    resp = _extract_json_string(body, '"response":"')
    notes = _extract_json_string(body, '"notes":"')
    if req is None and resp is None:
        log.warning(
            "upstream returned unparseable entry; skipping (head: %r)", piece[:80]
        )
        return None
    return (req or "", resp or "", notes)


def _extract_json_string(text: str, key: str) -> Optional[str]:
    """Extract a JSON-encoded string value that starts right after `key`.

    `key` includes the opening quote, e.g. ``\"request\":\"``. Decodes JSON
    string escape sequences. If the closing quote is missing (the source was
    truncated mid-string), returns the partial decoded value.
    """
    idx = text.find(key)
    if idx == -1:
        return None
    i = idx + len(key)
    out: list[str] = []
    simple = {"n": "\n", "r": "\r", "t": "\t", "b": "\b", "f": "\f", '"': '"', "\\": "\\", "/": "/"}
    while i < len(text):
        ch = text[i]
        if ch == '"':
            return "".join(out)
        if ch == "\\":
            if i + 1 >= len(text):
                return "".join(out)
            esc = text[i + 1]
            if esc in simple:
                out.append(simple[esc])
                i += 2
                continue
            if esc == "u":
                if i + 6 > len(text):
                    return "".join(out)
                try:
                    out.append(chr(int(text[i + 2 : i + 6], 16)))
                except ValueError:
                    return "".join(out)
                i += 6
                continue
            # Unknown escape — preserve raw backslash + next char.
            out.append(ch)
            i += 1
            continue
        out.append(ch)
        i += 1
    return "".join(out)


class UpstreamDenied(RuntimeError):
    """Burp denied history access (per-call approval, etc.)."""


class FixtureUpstream:
    """Loads pre-recorded history from a JSON fixture file. Used for offline
    development and tests without a live Burp instance.

    File format: a JSON array of objects with keys `request`, `response`,
    optional `notes`.
    """

    def __init__(self, path: str) -> None:
        self.path = path

    async def fetch(self, offset: int = 0) -> list[RawEntry]:
        with open(self.path, "r", encoding="utf-8") as f:
            data = json.load(f)
        all_entries: list[RawEntry] = [
            (obj.get("request") or "", obj.get("response") or "", obj.get("notes"))
            for obj in data
        ]
        return all_entries[offset:]
