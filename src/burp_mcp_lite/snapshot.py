"""In-memory history snapshot + stable id mapping.

The Burp upstream gives us proxy history as an ordered list with no per-entry
stable identifier. We assign session-local ids `0..N-1` in the order Burp
returned them. New entries Burp captured after our last refresh keep their
relative order and get appended ids — *existing ids stay stable*, which is
exactly the contract `view_request(id=42)` relies on.

Refresh policy is incremental. Burp's proxy history is monotonic-append in
practice, so:

  1. We re-fetch starting at `offset = N - 1` (our last known index).
  2. The first entry returned must equal our existing entry N-1 — that's our
     integrity anchor. Everything after it is new; we append.
  3. If the anchor mismatches (history was cleared or replaced inside Burp),
     we fall back to a full rebuild.

That's one upstream round-trip per refresh, returning ~1 entry when there's
no new traffic and ~K+1 entries when K were captured since last refresh.

Cache invalidation:
  - explicit `refresh=True` runs the incremental refresh (skips TTL check)
  - if the cache is older than `ttl_seconds`, the next access triggers it
  - a cache miss on a specific id does NOT auto-refresh — id stability matters
"""

from __future__ import annotations

import time
from dataclasses import dataclass, field
from typing import Awaitable, Callable, Optional

from .format.http_parse import ParsedRequest, ParsedResponse, parse_request, parse_response


@dataclass
class HistoryEntry:
    """One proxy history entry, parsed and ready for projection.

    `raw_request` / `raw_response` are kept verbatim so we can re-slice without
    losing fidelity. The parsed views are derived once.
    """

    id: int
    raw_request: str
    raw_response: str
    notes: Optional[str] = None
    request: ParsedRequest = field(init=False)
    response: ParsedResponse = field(init=False)

    def __post_init__(self) -> None:
        self.request = parse_request(self.raw_request or "")
        self.response = parse_response(self.raw_response or "")


@dataclass
class Snapshot:
    entries: list[HistoryEntry]
    fetched_at: float

    def by_id(self, id_: int) -> Optional[HistoryEntry]:
        if 0 <= id_ < len(self.entries):
            return self.entries[id_]
        return None

    @property
    def count(self) -> int:
        return len(self.entries)


# Type for the upstream fetcher: `fetch(offset=N)` returns the slice of history
# starting at `offset`. The upstream is responsible for any internal paging and
# for capping at its own max_entries.
RawEntry = tuple[str, str, Optional[str]]
Fetcher = Callable[..., Awaitable[list[RawEntry]]]


class SnapshotStore:
    """Owns the current snapshot and the refresh policy.

    Single-process, single-coroutine ownership assumed (we're stdio-driven,
    requests serialize). No locking.
    """

    def __init__(self, fetcher: Fetcher, ttl_seconds: float = 30.0) -> None:
        self._fetch = fetcher
        self._ttl = ttl_seconds
        self._snapshot: Optional[Snapshot] = None

    async def get(self, refresh: bool = False) -> Snapshot:
        now = time.monotonic()
        if (
            refresh
            or self._snapshot is None
            or (now - self._snapshot.fetched_at) > self._ttl
        ):
            await self._refresh()
        assert self._snapshot is not None
        return self._snapshot

    async def _refresh(self) -> None:
        existing = self._snapshot.entries if self._snapshot else []
        if not existing:
            await self._full_rebuild()
            return

        # Anchor probe + tail in one call. Page[0] should equal entry N-1.
        anchor_idx = len(existing) - 1
        page = await self._fetch(offset=anchor_idx)
        if not page:
            # Burp now has fewer entries than us — history was cleared.
            await self._full_rebuild()
            return

        anchor_req, anchor_resp, _ = page[0]
        last = existing[anchor_idx]
        if anchor_req != last.raw_request or anchor_resp != last.raw_response:
            # History was reordered or replaced; can't safely append.
            await self._full_rebuild()
            return

        new_raws = page[1:]
        if not new_raws:
            # No new traffic; just bump the cache timestamp.
            self._snapshot = Snapshot(entries=existing, fetched_at=time.monotonic())
            return

        start_id = len(existing)
        appended = [
            HistoryEntry(id=start_id + i, raw_request=r, raw_response=s, notes=n)
            for i, (r, s, n) in enumerate(new_raws)
        ]
        self._snapshot = Snapshot(
            entries=existing + appended, fetched_at=time.monotonic()
        )

    async def _full_rebuild(self) -> None:
        raws = await self._fetch(offset=0)
        entries = [
            HistoryEntry(id=i, raw_request=req, raw_response=resp, notes=notes)
            for i, (req, resp, notes) in enumerate(raws)
        ]
        self._snapshot = Snapshot(entries=entries, fetched_at=time.monotonic())

    def peek(self) -> Optional[Snapshot]:
        return self._snapshot
