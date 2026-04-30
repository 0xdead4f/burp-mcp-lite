"""list_history — browse + filter proxy history with field projection."""

from __future__ import annotations

from typing import Optional, Sequence, Union

from ..filters import apply_filter, build_filter
from ..format.render import (
    ALL_FIELDS,
    DEFAULT_FIELDS,
    render_history_ndjson,
    render_history_table,
)
from ..snapshot import SnapshotStore


async def list_history(
    store: SnapshotStore,
    *,
    limit: int = 20,
    offset: int = 0,
    fields: Optional[Sequence[str]] = None,
    host: Optional[str] = None,
    path: Optional[str] = None,
    method: Union[None, str, Sequence[str]] = None,
    status: Optional[str] = None,
    mime: Optional[str] = None,
    match: Optional[str] = None,
    match_in: str = "response.body",
    order: str = "latest",
    format: str = "text",
    refresh: bool = False,
) -> str:
    snap = await store.get(refresh=refresh)
    fil = build_filter(
        host=host,
        path=path,
        method=method,
        status=status,
        mime=mime,
        match=match,
        match_in=match_in,
    )
    filtered = apply_filter(snap.entries, fil)
    if order == "latest":
        # Newest entries first. Ids stay stable (latest entry has the highest
        # id); only the row order in the rendered table is reversed.
        filtered = list(reversed(filtered))
    total = len(filtered)
    page = filtered[offset : offset + max(0, limit)]

    if fields is None:
        active = list(DEFAULT_FIELDS)
    else:
        active = [f for f in fields if f in ALL_FIELDS]
        if not active:
            active = list(DEFAULT_FIELDS)

    if format == "json":
        return render_history_ndjson(page, active)
    return render_history_table(page, fields=active, total=total, offset=offset)
