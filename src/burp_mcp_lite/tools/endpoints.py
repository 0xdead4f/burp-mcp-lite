"""endpoints — deduplicated method+host+path inventory across the snapshot."""

from __future__ import annotations

from collections import Counter
from typing import Optional, Sequence, Union

from ..filters import build_filter, apply_filter
from ..format.render import render_endpoints
from ..snapshot import SnapshotStore


async def endpoints(
    store: SnapshotStore,
    *,
    host: Optional[str] = None,
    path: Optional[str] = None,
    method: Union[None, str, Sequence[str]] = None,
) -> str:
    snap = await store.get()
    fil = build_filter(host=host, path=path, method=method)
    entries = apply_filter(snap.entries, fil)

    counts: Counter[tuple[str, str, str]] = Counter()
    for e in entries:
        # Strip the query string for dedup; query params are noise for inventory.
        path_no_query = e.request.path.split("?", 1)[0]
        counts[(e.request.method, e.request.host, path_no_query)] += 1

    rows = sorted(
        [(m, h, p, n) for (m, h, p), n in counts.items()],
        key=lambda r: (-r[3], r[1], r[2]),
    )
    return render_endpoints(rows)
