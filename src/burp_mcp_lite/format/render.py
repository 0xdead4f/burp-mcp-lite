"""Text rendering for tool outputs.

The plain-text formats are tuned for token efficiency. Single source of truth
for column widths, headers, footers, and JSON variants.
"""

from __future__ import annotations

import json
from typing import Iterable, Optional, Sequence

from ..snapshot import HistoryEntry


# Default column ordering when `fields` is omitted.
DEFAULT_FIELDS: tuple[str, ...] = ("id", "method", "status", "host", "path", "len")
ALL_FIELDS: frozenset[str] = frozenset(
    {"id", "method", "status", "host", "path", "len", "mime", "time"}
)


def _entry_field(e: HistoryEntry, field: str) -> str:
    if field == "id":
        return str(e.id)
    if field == "method":
        return e.request.method or "-"
    if field == "status":
        return str(e.response.status) if e.response.status else "-"
    if field == "host":
        return e.request.host or "-"
    if field == "path":
        return e.request.path or "-"
    if field == "len":
        return _human_size(e.response.content_length)
    if field == "mime":
        return e.response.content_type or "-"
    if field == "time":
        # We don't have per-entry timestamps from upstream's serialization
        # (the official server doesn't include them). Placeholder for when we
        # add it.
        return "-"
    return "-"


def _human_size(n: int) -> str:
    if n <= 0:
        return "0"
    if n < 1024:
        return str(n)
    if n < 1024 * 1024:
        return f"{n / 1024:.1f}K"
    return f"{n / (1024 * 1024):.1f}M"


def render_history_table(
    entries: Sequence[HistoryEntry],
    *,
    fields: Sequence[str],
    total: int,
    offset: int,
) -> str:
    """Render a `list_history`-style table.

    Two-space gutter between columns. Column widths are sized to the longest
    cell or header, capped per-column to avoid one-row blowing up everything.
    """
    cap_for: dict[str, int] = {
        "host": 32,
        "path": 60,
        "mime": 24,
    }
    cells: list[list[str]] = []
    for e in entries:
        cells.append([_truncate(_entry_field(e, f), cap_for.get(f, 64)) for f in fields])

    widths: list[int] = []
    for i, f in enumerate(fields):
        col_max = max([len(row[i]) for row in cells] + [len(f)])
        widths.append(col_max)

    lines: list[str] = []
    lines.append("  ".join(f.ljust(widths[i]) for i, f in enumerate(fields)).rstrip())
    for row in cells:
        lines.append("  ".join(c.ljust(widths[i]) for i, c in enumerate(row)).rstrip())

    shown = len(entries)
    lines.append(f"-- {shown} of {total} (offset {offset}) --")
    return "\n".join(lines)


def render_history_ndjson(entries: Sequence[HistoryEntry], fields: Sequence[str]) -> str:
    out_lines: list[str] = []
    for e in entries:
        obj = {f: _ndjson_field(e, f) for f in fields}
        out_lines.append(json.dumps(obj, separators=(",", ":")))
    return "\n".join(out_lines)


def _ndjson_field(e: HistoryEntry, field: str):
    if field == "id":
        return e.id
    if field == "status":
        return e.response.status
    if field == "len":
        return e.response.content_length
    if field == "method":
        return e.request.method
    if field == "host":
        return e.request.host
    if field == "path":
        return e.request.path
    if field == "mime":
        return e.response.content_type
    return None


def _truncate(s: str, n: int) -> str:
    if len(s) <= n:
        return s
    if n <= 1:
        return "…"
    return s[: n - 1] + "…"


def render_request_view(
    e: HistoryEntry,
    *,
    headers: list[tuple[str, str]],
    body: str,
    show_headers: bool,
    note: Optional[str] = None,
) -> str:
    parts: list[str] = []
    scheme = _scheme_guess(e)
    line = f"[{e.id}] {e.request.method} {scheme}://{e.request.host}{e.request.path}"
    parts.append(line)
    if show_headers and headers:
        parts.append("\n".join(f"{k}: {v}" for k, v in headers))
    parts.append("")  # blank line before body
    if body:
        parts.append(body)
    else:
        parts.append("(no body)")
    if note:
        parts.append(note)
    return "\n".join(parts)


def render_response_view(
    e: HistoryEntry,
    *,
    headers: list[tuple[str, str]],
    body: str,
    show_headers: bool,
    note: Optional[str] = None,
) -> str:
    parts: list[str] = []
    size = _human_size(e.response.content_length)
    ct = e.response.content_type or "?"
    head = f"[{e.id}] {e.response.status} {e.response.reason}".rstrip()
    head += f"  ({size}, {ct})"
    parts.append(head)
    if show_headers and headers:
        parts.append("\n".join(f"{k}: {v}" for k, v in headers))
    parts.append("")
    if body:
        parts.append(body)
    else:
        parts.append("(no body)")
    if note:
        parts.append(note)
    return "\n".join(parts)


def _scheme_guess(e: HistoryEntry) -> str:
    # We don't get a scheme from the parsed request directly. Most Burp
    # proxy traffic is mixed; default to https unless a Host header had a
    # specific port hint that suggested http.
    return "https"


def render_match(
    *,
    matched: bool,
    target: str,
    hits: int,
    snippets: list[str],
) -> str:
    if not matched:
        return f"matched: false\ntarget: {target}"
    head = f"matched: true\ntarget: {target}\nhits: {hits}"
    if not snippets:
        return head
    return head + "\n" + "\n".join(snippets)


def render_endpoints(rows: Iterable[tuple[str, str, str, int]]) -> str:
    rows = list(rows)
    if not rows:
        return "(no endpoints)"
    method_w = max(len(r[0]) for r in rows)
    host_w = min(48, max(len(r[1]) for r in rows))
    path_w = min(64, max(len(r[2]) for r in rows))
    out: list[str] = []
    for method, host, path, count in rows:
        out.append(
            f"{method.ljust(method_w)}  {_truncate(host, host_w).ljust(host_w)}  "
            f"{_truncate(path, path_w).ljust(path_w)}  ×{count}"
        )
    return "\n".join(out)


def render_stats(
    *,
    total: int,
    by_method: dict[str, int],
    by_class: dict[str, int],
    by_host: list[tuple[str, int]],
) -> str:
    lines = [f"total entries: {total}"]
    if by_method:
        lines.append(
            "by method: "
            + ", ".join(f"{k}={v}" for k, v in sorted(by_method.items()))
        )
    if by_class:
        lines.append(
            "by status: "
            + ", ".join(f"{k}={v}" for k, v in sorted(by_class.items()))
        )
    if by_host:
        lines.append("top hosts:")
        for host, n in by_host:
            lines.append(f"  {host}  ×{n}")
    return "\n".join(lines)


def error_line(message: str) -> str:
    return f"error: {message}"
