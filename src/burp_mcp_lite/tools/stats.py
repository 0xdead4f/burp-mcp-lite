"""stats — small aggregate over the cached snapshot."""

from __future__ import annotations

from collections import Counter

from ..format.http_parse import status_class
from ..format.render import render_stats
from ..snapshot import SnapshotStore


async def stats(store: SnapshotStore) -> str:
    snap = await store.get()
    by_method: Counter[str] = Counter()
    by_class: Counter[str] = Counter()
    by_host: Counter[str] = Counter()
    for e in snap.entries:
        by_method[e.request.method or "-"] += 1
        by_class[status_class(e.response.status)] += 1
        if e.request.host:
            by_host[e.request.host] += 1
    top_hosts = by_host.most_common(5)
    return render_stats(
        total=snap.count,
        by_method=dict(by_method),
        by_class=dict(by_class),
        by_host=top_hosts,
    )
