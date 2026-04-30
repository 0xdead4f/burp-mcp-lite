import { describe, it, expect } from "vitest";
import {
  applyFilter,
  buildFilter,
  parseStatusFilter,
  resolveMatchTarget,
} from "../src/filters.js";
import type { HistoryEntry } from "../src/snapshot.js";

function entry(partial: {
  id?: number;
  method?: string;
  host?: string;
  path?: string;
  status?: number;
  contentType?: string;
  reqBody?: string;
  respBody?: string;
  reqHeaders?: ReadonlyArray<readonly [string, string]>;
  respHeaders?: ReadonlyArray<readonly [string, string]>;
}): HistoryEntry {
  return {
    id: partial.id ?? 0,
    rawRequest: "",
    rawResponse: "",
    notes: null,
    observedAt: 0,
    request: {
      method: partial.method ?? "GET",
      target: partial.path ?? "/",
      version: "HTTP/1.1",
      headers: partial.reqHeaders ?? [],
      body: partial.reqBody ?? "",
      host: partial.host ?? "example.com",
      path: partial.path ?? "/",
    },
    response: {
      version: "HTTP/1.1",
      status: partial.status ?? 200,
      reason: "OK",
      headers: partial.respHeaders ?? [],
      body: partial.respBody ?? "",
      contentType: partial.contentType ?? "application/json",
      contentLength: (partial.respBody ?? "").length,
    },
  };
}

describe("parseStatusFilter", () => {
  it("matches an exact status", () => {
    const p = parseStatusFilter("200");
    expect(p(200)).toBe(true);
    expect(p(404)).toBe(false);
  });
  it("matches a class", () => {
    const p = parseStatusFilter("4xx");
    expect(p(404)).toBe(true);
    expect(p(500)).toBe(false);
    expect(p(399)).toBe(false);
  });
  it("matches a comma list", () => {
    const p = parseStatusFilter("200,4xx,500");
    expect(p(200)).toBe(true);
    expect(p(401)).toBe(true);
    expect(p(500)).toBe(true);
    expect(p(301)).toBe(false);
  });
  it("matches a numeric range", () => {
    const p = parseStatusFilter("400-499");
    expect(p(400)).toBe(true);
    expect(p(499)).toBe(true);
    expect(p(500)).toBe(false);
  });
  it("matches a class range", () => {
    const p = parseStatusFilter("4xx-5xx");
    expect(p(400)).toBe(true);
    expect(p(599)).toBe(true);
    expect(p(399)).toBe(false);
  });
  it("unknown tokens never match", () => {
    const p = parseStatusFilter("zzz");
    expect(p(200)).toBe(false);
    expect(p(404)).toBe(false);
  });
});

describe("buildFilter", () => {
  const entries: HistoryEntry[] = [
    entry({ id: 0, method: "GET", host: "api.example.com", path: "/v1/users", status: 200 }),
    entry({ id: 1, method: "POST", host: "api.example.com", path: "/v1/login", status: 401 }),
    entry({ id: 2, method: "GET", host: "cdn.example.com", path: "/static/app.js", status: 200, contentType: "application/javascript" }),
    entry({ id: 3, method: "POST", host: "api.example.com", path: "/v1/checkout", status: 500 }),
  ];

  it("filters by method", () => {
    const out = applyFilter(entries, buildFilter({ method: "POST" }));
    expect(out.map((e) => e.id)).toEqual([1, 3]);
  });
  it("filters by method list (string and array)", () => {
    expect(applyFilter(entries, buildFilter({ method: "GET,POST" })).length).toBe(4);
    expect(applyFilter(entries, buildFilter({ method: ["GET"] })).length).toBe(2);
  });
  it("filters by host substring (case-insensitive)", () => {
    const out = applyFilter(entries, buildFilter({ host: "CDN" }));
    expect(out.map((e) => e.id)).toEqual([2]);
  });
  it("filters by path regex", () => {
    const out = applyFilter(entries, buildFilter({ path: "/v1/(login|checkout)" }));
    expect(out.map((e) => e.id)).toEqual([1, 3]);
  });
  it("filters by status class", () => {
    const out = applyFilter(entries, buildFilter({ status: "4xx-5xx" }));
    expect(out.map((e) => e.id)).toEqual([1, 3]);
  });
  it("filters by mime substring", () => {
    const out = applyFilter(entries, buildFilter({ mime: "javascript" }));
    expect(out.map((e) => e.id)).toEqual([2]);
  });
  it("regex match across response body", () => {
    const e = entry({ id: 9, respBody: '{"token":"eyJabc"}' });
    const out = applyFilter([e], buildFilter({ match: "eyJ", matchIn: "response.body" }));
    expect(out.length).toBe(1);
  });
});

describe("resolveMatchTarget", () => {
  const e = entry({
    reqHeaders: [["Authorization", "Bearer xyz"]],
    reqBody: "req-body",
    respHeaders: [["Content-Type", "application/json"]],
    respBody: "resp-body",
  });
  it("returns response body", () => {
    expect(resolveMatchTarget(e, "response.body")).toBe("resp-body");
  });
  it("returns request all", () => {
    const out = resolveMatchTarget(e, "request.all");
    expect(out).toContain("Authorization: Bearer xyz");
    expect(out).toContain("\n\nreq-body");
  });
  it("falls back to response body for unknown targets", () => {
    expect(resolveMatchTarget(e, "weird.thing")).toBe("resp-body");
  });
});
