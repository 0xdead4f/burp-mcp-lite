"""Test the incremental refresh behavior of SnapshotStore.

The refresh fetches `offset = N - 1` (anchor + tail) in one call. We verify:
  - first call does a full rebuild
  - subsequent refreshes only ask upstream for the tail
  - existing ids stay stable across refreshes
  - if the anchor mismatches (history cleared/reordered), we rebuild
"""

from __future__ import annotations

import pytest

from burp_mcp_lite.snapshot import SnapshotStore


def _e(tag: str):
    return (
        f"GET /{tag} HTTP/1.1\r\nHost: a\r\n\r\n",
        f"HTTP/1.1 200 OK\r\nContent-Type: text/plain\r\n\r\n{tag}",
        None,
    )


class FakeUpstream:
    """Recordable upstream that lets us assert on offsets requested."""

    def __init__(self, entries):
        self.entries = list(entries)
        self.calls: list[int] = []

    async def fetch(self, offset: int = 0):
        self.calls.append(offset)
        return list(self.entries[offset:])


@pytest.mark.asyncio
async def test_first_call_is_full_rebuild():
    up = FakeUpstream([_e("a"), _e("b"), _e("c")])
    store = SnapshotStore(up.fetch, ttl_seconds=999)
    snap = await store.get()
    assert snap.count == 3
    assert up.calls == [0]


@pytest.mark.asyncio
async def test_refresh_no_new_traffic_pulls_only_anchor():
    up = FakeUpstream([_e("a"), _e("b"), _e("c")])
    store = SnapshotStore(up.fetch, ttl_seconds=999)
    await store.get()
    up.calls.clear()

    snap = await store.get(refresh=True)
    assert snap.count == 3
    # Should have fetched only from offset=2 (the anchor at index N-1)
    assert up.calls == [2]


@pytest.mark.asyncio
async def test_refresh_appends_new_entries_with_stable_ids():
    up = FakeUpstream([_e("a"), _e("b"), _e("c")])
    store = SnapshotStore(up.fetch, ttl_seconds=999)
    snap1 = await store.get()
    original_id_for_a = snap1.entries[0].id
    original_id_for_c = snap1.entries[2].id

    # Burp captures two more entries
    up.entries.extend([_e("d"), _e("e")])
    up.calls.clear()

    snap2 = await store.get(refresh=True)
    assert snap2.count == 5
    # One upstream call, anchor at offset 2
    assert up.calls == [2]
    # Existing ids stay put
    assert snap2.entries[0].id == original_id_for_a
    assert snap2.entries[2].id == original_id_for_c
    # New ids are appended
    assert snap2.entries[3].id == 3
    assert snap2.entries[4].id == 4
    # Content of new entries is correct
    assert "d" in snap2.entries[3].raw_response


@pytest.mark.asyncio
async def test_refresh_falls_back_to_full_rebuild_on_anchor_mismatch():
    up = FakeUpstream([_e("a"), _e("b"), _e("c")])
    store = SnapshotStore(up.fetch, ttl_seconds=999)
    snap1 = await store.get()
    assert snap1.count == 3

    # Burp's history was cleared and refilled with different entries.
    up.entries = [_e("x"), _e("y")]
    up.calls.clear()

    snap2 = await store.get(refresh=True)
    # Anchor probe at offset=2 returns nothing (or mismatches), so we rebuild.
    # First call: anchor probe at offset=2 (returns empty here). Then full rebuild at offset=0.
    assert 0 in up.calls
    assert snap2.count == 2
    assert "x" in snap2.entries[0].raw_response


@pytest.mark.asyncio
async def test_refresh_falls_back_when_anchor_content_differs():
    up = FakeUpstream([_e("a"), _e("b"), _e("c")])
    store = SnapshotStore(up.fetch, ttl_seconds=999)
    await store.get()

    # Same length, but entry at index 2 is now different content.
    up.entries[2] = _e("DIFFERENT")
    up.calls.clear()

    snap = await store.get(refresh=True)
    # Anchor probe + full rebuild
    assert up.calls == [2, 0]
    assert "DIFFERENT" in snap.entries[2].raw_response


@pytest.mark.asyncio
async def test_within_ttl_no_upstream_call():
    up = FakeUpstream([_e("a"), _e("b")])
    store = SnapshotStore(up.fetch, ttl_seconds=999)
    await store.get()
    up.calls.clear()

    # Multiple gets within TTL window — no upstream calls.
    await store.get()
    await store.get()
    await store.get()
    assert up.calls == []


@pytest.mark.asyncio
async def test_empty_upstream_then_appended_handled():
    up = FakeUpstream([])
    store = SnapshotStore(up.fetch, ttl_seconds=999)
    snap1 = await store.get()
    assert snap1.count == 0

    up.entries = [_e("a")]
    up.calls.clear()
    snap2 = await store.get(refresh=True)
    # No anchor possible (existing was empty), so we full-rebuild.
    assert up.calls == [0]
    assert snap2.count == 1
