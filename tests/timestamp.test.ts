// Per-entry timestamp resolution. Source priority for entryTimestamp(e):
//   1. Response Date header (per-entry, server clock).
//   2. observedAt (when our middleware ingested the entry).

import { describe, it, expect } from "vitest";
import { entryTimestamp, SnapshotStore } from "../src/snapshot.js";
import { formatEntryTime } from "../src/format/render.js";
import { listHistory } from "../src/tools/list-history.js";
import { viewRequest, viewResponse } from "../src/tools/view.js";
import { buildSampleStore } from "./helpers.js";

function entryWith(rawResponse: string) {
  // Build a one-entry store and pull the entry out of it so observedAt is set.
  const store = new SnapshotStore(async () => [
    { request: "GET / HTTP/1.1\r\nHost: a\r\n\r\n", response: rawResponse, notes: null },
  ], 999);
  return { store };
}

describe("entryTimestamp", () => {
  it("uses the response Date header when present", async () => {
    const { store } = entryWith(
      "HTTP/1.1 200 OK\r\nDate: Wed, 21 Oct 2015 07:28:00 GMT\r\nContent-Type: text/plain\r\n\r\nbody",
    );
    const snap = await store.get();
    const ts = entryTimestamp(snap.entries[0]!);
    expect(ts.getUTCFullYear()).toBe(2015);
    expect(ts.getUTCMonth()).toBe(9); // October (0-indexed)
    expect(ts.getUTCDate()).toBe(21);
  });

  it("falls back to observedAt when no Date header", async () => {
    const before = Date.now();
    const { store } = entryWith(
      "HTTP/1.1 200 OK\r\nContent-Type: text/plain\r\n\r\nbody",
    );
    const snap = await store.get();
    const after = Date.now();
    const ts = entryTimestamp(snap.entries[0]!).getTime();
    expect(ts).toBeGreaterThanOrEqual(before - 1000);
    expect(ts).toBeLessThanOrEqual(after + 1000);
  });

  it("falls back when Date header is unparseable", async () => {
    const { store } = entryWith(
      "HTTP/1.1 200 OK\r\nDate: not a real date\r\n\r\nbody",
    );
    const snap = await store.get();
    const ts = entryTimestamp(snap.entries[0]!).getTime();
    expect(Math.abs(ts - Date.now())).toBeLessThan(5000);
  });
});

describe("formatEntryTime", () => {
  it("renders HH:MM:SS for today", async () => {
    const { store } = entryWith(
      "HTTP/1.1 200 OK\r\nContent-Type: text/plain\r\n\r\nbody",
    );
    const snap = await store.get();
    const out = formatEntryTime(snap.entries[0]!);
    expect(out.length).toBe(8);
    expect(out[2]).toBe(":");
    expect(out[5]).toBe(":");
  });

  it("renders MM-DD HH:MM:SS for other days", async () => {
    const { store } = entryWith(
      "HTTP/1.1 200 OK\r\nDate: Wed, 21 Oct 2015 07:28:00 GMT\r\n\r\nbody",
    );
    const snap = await store.get();
    const out = formatEntryTime(snap.entries[0]!);
    expect(out.length).toBe(14);
    expect(out).toContain("10-21");
  });
});

describe("integration: time field renders non-dash in list_history", () => {
  it("time column is populated", async () => {
    const store = buildSampleStore();
    const out = await listHistory(store, { fields: ["id", "time"] });
    const rows = out
      .split("\n")
      .filter((l) => l && !l.startsWith("--") && !l.startsWith("id"));
    expect(rows.length).toBeGreaterThan(0);
    for (const row of rows) {
      const cells = row.split(/\s+/);
      expect(cells.length).toBeGreaterThanOrEqual(2);
      expect(cells[1]).not.toBe("-");
    }
  });
});

describe("integration: view headers include time", () => {
  it("view_request header line ends with (HH:MM:SS)", async () => {
    const store = buildSampleStore();
    const out = await viewRequest(store, { id: 0 });
    const first = out.split("\n")[0]!;
    expect(first.startsWith("[0] GET")).toBe(true);
    expect(first.trimEnd().endsWith(")")).toBe(true);
    const paren = first.split("(").pop()!.replace(")", "");
    expect(paren).toContain(":");
  });

  it("view_response header line includes time as the third paren-tuple field", async () => {
    const store = buildSampleStore();
    const out = await viewResponse(store, { id: 0 });
    const first = out.split("\n")[0]!;
    expect(first.startsWith("[0] 200")).toBe(true);
    const suffix = first.split("(").pop()!.replace(")", "");
    const parts = suffix.split(",").map((s) => s.trim());
    expect(parts.length).toBe(3);
    expect(parts[2]).toContain(":");
  });
});
