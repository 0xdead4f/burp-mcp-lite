import { describe, it, expect } from "vitest";
import { buildSampleStore } from "./helpers.js";
import { listHistory } from "../src/tools/list-history.js";
import { viewRequest, viewResponse } from "../src/tools/view.js";
import { match } from "../src/tools/match.js";
import { endpoints } from "../src/tools/endpoints.js";
import { stats } from "../src/tools/stats.js";
import { SnapshotStore } from "../src/snapshot.js";

describe("list_history", () => {
  it("default returns a footer and column headers", async () => {
    const store = buildSampleStore();
    const out = await listHistory(store);
    expect(out).toContain("id");
    expect(out).toContain("method");
    expect(out).toContain("-- 6 of 6 (offset 0) --");
  });

  it("filters by method=POST", async () => {
    const store = buildSampleStore();
    const out = await listHistory(store, { method: "POST" });
    expect(out).toContain("-- 2 of 2");
  });

  it("filters by status class 4xx-5xx", async () => {
    const store = buildSampleStore();
    const out = await listHistory(store, { status: "4xx-5xx" });
    expect(out).toContain("-- 2 of 2");
  });

  it("regex match on response body narrows", async () => {
    const store = buildSampleStore();
    const out = await listHistory(store, { match: "eyJhbGc" });
    expect(out).toContain("-- 1 of 1");
  });

  it("field projection picks the columns", async () => {
    const store = buildSampleStore();
    const out = await listHistory(store, { fields: ["id", "method", "path"] });
    const firstLine = out.split("\n")[0]!;
    expect(firstLine.split(/\s+/)).toEqual(["id", "method", "path"]);
  });

  it("ndjson default order is latest-first", async () => {
    const store = buildSampleStore();
    const out = await listHistory(store, {
      format: "json",
      fields: ["id", "method", "status"],
    });
    const objs = out.split("\n").map((l) => JSON.parse(l));
    expect(objs.length).toBe(6);
    expect(objs[0].id).toBe(5);
    expect(objs.map((o: { id: number }) => o.id)).toEqual([5, 4, 3, 2, 1, 0]);
  });

  it("oldest order is ascending", async () => {
    const store = buildSampleStore();
    const out = await listHistory(store, {
      format: "json",
      fields: ["id"],
      order: "oldest",
    });
    const ids = out.split("\n").map((l) => JSON.parse(l).id);
    expect(ids).toEqual([0, 1, 2, 3, 4, 5]);
  });

  it("pagination follows order", async () => {
    const store = buildSampleStore();
    const out = await listHistory(store, {
      limit: 2,
      format: "json",
      fields: ["id"],
    });
    const ids = out.split("\n").map((l) => JSON.parse(l).id);
    expect(ids).toEqual([5, 4]);
  });

  it("default table is latest-first", async () => {
    const store = buildSampleStore();
    const out = await listHistory(store);
    const dataRows = out
      .split("\n")
      .filter(
        (l) => l && !l.startsWith("--") && !l.startsWith("id"),
      );
    expect(dataRows[0]!.split(/\s+/)[0]).toBe("5");
  });
});

describe("view_request", () => {
  it("default hides Authorization headers", async () => {
    const store = buildSampleStore();
    const out = await viewRequest(store, { id: 0 });
    expect(out).toContain("[0] GET");
    expect(out).not.toContain("Authorization");
    expect(out).toContain("(no body)");
  });

  it("with include_headers redacts Authorization value", async () => {
    const store = buildSampleStore();
    const out = await viewRequest(store, { id: 0, includeHeaders: true, redact: true });
    expect(out).toContain("Authorization: <redacted");
    expect(out).not.toContain("Bearer");
  });

  it("redact=false reveals raw bytes", async () => {
    const store = buildSampleStore();
    const out = await viewRequest(store, { id: 0, includeHeaders: true, redact: false });
    expect(out).toContain("Bearer eyJabc.def.ghi");
  });

  it("regex body slice keeps the matching line", async () => {
    const store = buildSampleStore();
    const out = await viewRequest(store, { id: 1, body: "/hunter/" });
    expect(out).toContain("hunter2");
  });
});

describe("view_response", () => {
  it("auto-truncates large bodies", async () => {
    const store = buildSampleStore();
    const out = await viewResponse(store, { id: 3 });
    expect(out).toContain("auto-truncated");
  });

  it("body=full overrides auto-truncation", async () => {
    const store = buildSampleStore();
    const out = await viewResponse(store, { id: 3, body: "full" });
    expect(out).not.toContain("auto-truncated");
  });

  it("set-cookie-only mode shows just the Set-Cookie header", async () => {
    const store = buildSampleStore();
    const out = await viewResponse(store, {
      id: 2,
      includeSetCookie: true,
      redact: false,
    });
    expect(out).toContain("Set-Cookie: session=abc; HttpOnly");
    expect(out).not.toContain("Location:");
  });

  it("returns an error string for unknown id", async () => {
    const store = buildSampleStore();
    const out = await viewResponse(store, { id: 999 });
    expect(out.startsWith("error:")).toBe(true);
    expect(out).toContain("not found");
  });
});

describe("match", () => {
  it("hits in response body", async () => {
    const store = buildSampleStore();
    const out = await match(store, { id: 4, pattern: "token" });
    expect(out).toContain("matched: true");
    expect(out).toContain("hits: 1");
    expect(out).toContain('"token"');
  });

  it("misses produce a tiny output", async () => {
    const store = buildSampleStore();
    const out = await match(store, { id: 4, pattern: "nosuchstring" });
    expect(out).toBe("matched: false\ntarget: response.body");
  });

  it("matches across request headers", async () => {
    const store = buildSampleStore();
    const out = await match(store, {
      id: 0,
      pattern: "Bearer",
      target: "request.headers",
    });
    expect(out).toContain("matched: true");
  });

  it("invalid regex returns an error string", async () => {
    const store = buildSampleStore();
    const out = await match(store, { id: 0, pattern: "[" });
    expect(out.startsWith("error:")).toBe(true);
  });

  it("caps an extremely long matching line via windowedLine", async () => {
    const longBody = "x".repeat(2000) + "NEEDLE" + "y".repeat(2000);
    const rawReq = "GET / HTTP/1.1\r\nHost: a\r\n\r\n";
    const rawResp = "HTTP/1.1 200 OK\r\nContent-Type: text/plain\r\n\r\n" + longBody;
    const store = new SnapshotStore(async () => [
      { request: rawReq, response: rawResp, notes: null },
    ], 999);

    const out = await match(store, { id: 0, pattern: "NEEDLE" });
    expect(out).toContain("matched: true");
    const snippetLines = out.split("\n").filter((l) => l.startsWith("[L"));
    expect(snippetLines.length).toBeGreaterThan(0);
    for (const l of snippetLines) {
      expect(l.length).toBeLessThan(400);
    }
    expect(snippetLines.some((l) => l.includes("NEEDLE"))).toBe(true);
  });
});

describe("endpoints", () => {
  it("dedups across history", async () => {
    const store = buildSampleStore();
    const out = await endpoints(store);
    expect(out).toContain("api.example.com");
    expect(out).toContain("cdn.example.com");
  });

  it("filters by host substring", async () => {
    const store = buildSampleStore();
    const out = await endpoints(store, { host: "cdn" });
    expect(out).not.toContain("api.example.com");
    expect(out).toContain("cdn.example.com");
  });
});

describe("stats", () => {
  it("aggregates the snapshot", async () => {
    const store = buildSampleStore();
    const out = await stats(store);
    expect(out).toContain("total entries: 6");
    expect(out).toContain("GET=");
    expect(out).toContain("POST=");
    expect(out).toContain("2xx=");
    expect(out).toContain("4xx=");
    expect(out).toContain("5xx=");
  });
});
