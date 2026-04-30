import { describe, it, expect } from "vitest";
import { SnapshotStore, type RawEntry } from "../src/snapshot.js";

function rawEntry(tag: string): RawEntry {
  return {
    request: `GET /${tag} HTTP/1.1\r\nHost: a\r\n\r\n`,
    response: `HTTP/1.1 200 OK\r\nContent-Type: text/plain\r\n\r\n${tag}`,
    notes: null,
  };
}

class FakeUpstream {
  entries: RawEntry[];
  calls: number[] = [];
  constructor(entries: RawEntry[]) {
    this.entries = [...entries];
  }
  fetch = async (offset = 0): Promise<RawEntry[]> => {
    this.calls.push(offset);
    return this.entries.slice(offset);
  };
}

describe("SnapshotStore refresh", () => {
  it("first call is a full rebuild from offset 0", async () => {
    const up = new FakeUpstream([rawEntry("a"), rawEntry("b"), rawEntry("c")]);
    const store = new SnapshotStore(up.fetch, 999);
    const snap = await store.get();
    expect(snap.count).toBe(3);
    expect(up.calls).toEqual([0]);
  });

  it("refresh with no new traffic pulls only the anchor", async () => {
    const up = new FakeUpstream([rawEntry("a"), rawEntry("b"), rawEntry("c")]);
    const store = new SnapshotStore(up.fetch, 999);
    await store.get();
    up.calls.length = 0;

    const snap = await store.get(true);
    expect(snap.count).toBe(3);
    expect(up.calls).toEqual([2]);
  });

  it("refresh appends new entries with stable ids", async () => {
    const up = new FakeUpstream([rawEntry("a"), rawEntry("b"), rawEntry("c")]);
    const store = new SnapshotStore(up.fetch, 999);
    const snap1 = await store.get();
    const idForA = snap1.entries[0]!.id;
    const idForC = snap1.entries[2]!.id;

    up.entries.push(rawEntry("d"), rawEntry("e"));
    up.calls.length = 0;

    const snap2 = await store.get(true);
    expect(snap2.count).toBe(5);
    expect(up.calls).toEqual([2]);
    expect(snap2.entries[0]!.id).toBe(idForA);
    expect(snap2.entries[2]!.id).toBe(idForC);
    expect(snap2.entries[3]!.id).toBe(3);
    expect(snap2.entries[4]!.id).toBe(4);
    expect(snap2.entries[3]!.rawResponse).toContain("d");
  });

  it("falls back to full rebuild on anchor mismatch (history cleared)", async () => {
    const up = new FakeUpstream([rawEntry("a"), rawEntry("b"), rawEntry("c")]);
    const store = new SnapshotStore(up.fetch, 999);
    const snap1 = await store.get();
    expect(snap1.count).toBe(3);

    up.entries = [rawEntry("x"), rawEntry("y")];
    up.calls.length = 0;

    const snap2 = await store.get(true);
    expect(up.calls).toContain(0);
    expect(snap2.count).toBe(2);
    expect(snap2.entries[0]!.rawResponse).toContain("x");
  });

  it("falls back when anchor content differs", async () => {
    const up = new FakeUpstream([rawEntry("a"), rawEntry("b"), rawEntry("c")]);
    const store = new SnapshotStore(up.fetch, 999);
    await store.get();

    up.entries[2] = rawEntry("DIFFERENT");
    up.calls.length = 0;

    const snap = await store.get(true);
    expect(up.calls).toEqual([2, 0]);
    expect(snap.entries[2]!.rawResponse).toContain("DIFFERENT");
  });

  it("within ttl: no upstream calls", async () => {
    const up = new FakeUpstream([rawEntry("a"), rawEntry("b")]);
    const store = new SnapshotStore(up.fetch, 999);
    await store.get();
    up.calls.length = 0;
    await store.get();
    await store.get();
    expect(up.calls).toEqual([]);
  });

  it("empty upstream then appended is handled", async () => {
    const up = new FakeUpstream([]);
    const store = new SnapshotStore(up.fetch, 999);
    const snap1 = await store.get();
    expect(snap1.count).toBe(0);

    up.entries = [rawEntry("a")];
    up.calls.length = 0;
    const snap2 = await store.get(true);
    expect(up.calls).toEqual([0]);
    expect(snap2.count).toBe(1);
  });
});
