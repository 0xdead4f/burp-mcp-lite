"""match — predicate query over a single entry, returns matched + small evidence.

Targets:
  request.body | request.headers | request.all
  response.body | response.headers | response.all
"""

from __future__ import annotations

import re

from ..filters import _resolve_match_target  # internal but precise reuse
from ..format.render import error_line, render_match
from ..snapshot import SnapshotStore


async def match(
    store: SnapshotStore,
    *,
    id: int,
    pattern: str,
    target: str = "response.body",
    case_sensitive: bool = False,
    context: int = 0,
    max_hits: int = 10,
) -> str:
    snap = await store.get()
    e = snap.by_id(id)
    if e is None:
        return error_line(
            f"id {id} not found in current history snapshot (have ids 0–{snap.count - 1 if snap.count else -1})"
        )
    try:
        rx = re.compile(pattern, 0 if case_sensitive else re.IGNORECASE)
    except re.error as exc:
        return error_line(f"invalid regex {pattern!r}: {exc}")

    text = _resolve_match_target(e, target)
    lines = text.replace("\r\n", "\n").split("\n") if text else []

    hit_indices: list[int] = [i for i, line in enumerate(lines) if rx.search(line)]
    if not hit_indices:
        return render_match(matched=False, target=target, hits=0, snippets=[])

    snippets: list[str] = []
    rendered: set[int] = set()
    for i in hit_indices[:max_hits]:
        lo = max(0, i - context)
        hi = min(len(lines), i + context + 1)
        if snippets and lo > max(rendered) + 1:
            snippets.append("...")
        for j in range(lo, hi):
            if j in rendered:
                continue
            rendered.add(j)
            prefix = f"[L{j + 1}] "
            snippets.append(prefix + lines[j])

    return render_match(
        matched=True, target=target, hits=len(hit_indices), snippets=snippets
    )
