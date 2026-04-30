"""view_request and view_response — single-entry inspection.

Defaults: headers off, cookies off, redact on. Body slicing inherits the same
DSL as `match`.
"""

from __future__ import annotations

from typing import Optional

from ..format.redact import apply_redaction
from ..format.render import error_line, render_request_view, render_response_view
from ..format.slice import slice_body
from ..snapshot import SnapshotStore

# Above this size (bytes), `body="auto"` switches from full to head:20.
AUTO_FULL_THRESHOLD = 4 * 1024


def _filter_headers(
    headers: list[tuple[str, str]],
    *,
    include_cookies: bool,
    cookie_name: str,
) -> list[tuple[str, str]]:
    if include_cookies:
        return headers
    return [(k, v) for k, v in headers if k.lower() != cookie_name.lower()]


def _resolve_body_spec(spec: str, body_size: int) -> str:
    if spec.lower() == "auto":
        return "full" if body_size <= AUTO_FULL_THRESHOLD else "head:20"
    return spec


async def view_request(
    store: SnapshotStore,
    *,
    id: int,
    include_headers: bool = False,
    include_cookies: bool = False,
    redact: bool = True,
    body: str = "full",
    context: int = 1,
) -> str:
    snap = await store.get()
    e = snap.by_id(id)
    if e is None:
        return error_line(
            f"id {id} not found in current history snapshot (have ids 0–{snap.count - 1 if snap.count else -1})"
        )

    # When headers are off but include_cookies is on, we still emit the Cookie
    # header alone — the "auth context only" workflow.
    if not include_headers and include_cookies:
        chosen = [(k, v) for k, v in e.request.headers if k.lower() == "cookie"]
        show_headers = bool(chosen)
    else:
        chosen = _filter_headers(e.request.headers, include_cookies=include_cookies, cookie_name="Cookie")
        show_headers = include_headers

    chosen = apply_redaction(chosen, redact=redact)

    body_spec = _resolve_body_spec(body, len(e.request.body))
    sliced = slice_body(e.request.body, body_spec, context=context)
    note = None
    if sliced.truncated and body_spec.startswith(("head:", "tail:")):
        note = f"... ({sliced.total_lines} lines total; truncated)"
    return render_request_view(
        e,
        headers=chosen,
        body=sliced.text,
        show_headers=show_headers,
        note=note,
    )


async def view_response(
    store: SnapshotStore,
    *,
    id: int,
    include_headers: bool = False,
    include_set_cookie: bool = False,
    redact: bool = True,
    body: str = "auto",
    context: int = 1,
) -> str:
    snap = await store.get()
    e = snap.by_id(id)
    if e is None:
        return error_line(
            f"id {id} not found in current history snapshot (have ids 0–{snap.count - 1 if snap.count else -1})"
        )

    if not include_headers and include_set_cookie:
        chosen = [(k, v) for k, v in e.response.headers if k.lower() == "set-cookie"]
        show_headers = bool(chosen)
    else:
        chosen = _filter_headers(
            e.response.headers, include_cookies=include_set_cookie, cookie_name="Set-Cookie"
        )
        show_headers = include_headers

    chosen = apply_redaction(chosen, redact=redact)

    body_spec = _resolve_body_spec(body, len(e.response.body))
    sliced = slice_body(e.response.body, body_spec, context=context)
    note = None
    if body == "auto" and body_spec != "full":
        note = (
            f"... (auto-truncated; body is {len(e.response.body)} bytes — "
            f'pass body="full" to override)'
        )
    elif sliced.truncated and body_spec.startswith(("head:", "tail:")):
        note = f"... ({sliced.total_lines} lines total; truncated)"

    return render_response_view(
        e,
        headers=chosen,
        body=sliced.text,
        show_headers=show_headers,
        note=note,
    )
