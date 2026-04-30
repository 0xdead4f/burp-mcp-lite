import { describe, it, expect } from "vitest";
import {
  parseRequest,
  parseResponse,
  requestHost,
  requestPath,
  responseContentType,
  responseContentLength,
  findHeader,
  statusClass,
} from "../src/format/http-parse.js";

describe("parseRequest", () => {
  it("parses a simple GET", () => {
    const raw =
      "GET /v1/users?id=42 HTTP/1.1\r\nHost: api.example.com\r\nAccept: */*\r\n\r\n";
    const r = parseRequest(raw);
    expect(r.method).toBe("GET");
    expect(r.target).toBe("/v1/users?id=42");
    expect(r.version).toBe("HTTP/1.1");
    expect(requestHost(r)).toBe("api.example.com");
    expect(requestPath(r)).toBe("/v1/users?id=42");
    expect(findHeader(r.headers, "accept")).toBe("*/*");
    expect(r.body).toBe("");
  });

  it("parses request with body", () => {
    const raw =
      "POST /login HTTP/1.1\r\nHost: a\r\nContent-Type: application/json\r\n\r\n{\"u\":\"x\"}";
    const r = parseRequest(raw);
    expect(r.method).toBe("POST");
    expect(r.body).toBe('{"u":"x"}');
  });

  it("strips scheme/authority from absolute-URI request-target", () => {
    const raw = "GET https://example.com:8443/abc?q=1 HTTP/1.1\r\nHost: x\r\n\r\n";
    const r = parseRequest(raw);
    expect(requestPath(r)).toBe("/abc?q=1");
  });

  it("accepts LF-only line endings", () => {
    const r = parseRequest("GET / HTTP/1.1\nHost: a\n\nbody");
    expect(r.method).toBe("GET");
    expect(r.body).toBe("body");
    expect(findHeader(r.headers, "host")).toBe("a");
  });

  it("handles malformed headers without dropping them", () => {
    const r = parseRequest("GET / HTTP/1.1\r\nHost: a\r\nWeirdLineNoColon\r\n\r\n");
    const names = r.headers.map(([k]) => k);
    expect(names).toContain("WeirdLineNoColon");
  });

  it("returns empty parsed shape for empty input", () => {
    const r = parseRequest("");
    expect(r.method).toBe("");
    expect(r.body).toBe("");
  });
});

describe("parseResponse", () => {
  it("parses a 200 response", () => {
    const raw = "HTTP/1.1 200 OK\r\nContent-Type: application/json; charset=utf-8\r\n\r\n{}";
    const r = parseResponse(raw);
    expect(r.version).toBe("HTTP/1.1");
    expect(r.status).toBe(200);
    expect(r.reason).toBe("OK");
    expect(responseContentType(r)).toBe("application/json");
    expect(r.body).toBe("{}");
  });

  it("parses a multi-word reason", () => {
    const r = parseResponse("HTTP/1.1 400 Bad Request\r\n\r\n");
    expect(r.status).toBe(400);
    expect(r.reason).toBe("Bad Request");
  });

  it("status defaults to 0 when unparseable", () => {
    const r = parseResponse("HTTP/1.1 ZZZ Bad\r\n\r\n");
    expect(r.status).toBe(0);
    expect(statusClass(r.status)).toBe("0xx");
  });

  it("content-length falls back to header when body is empty", () => {
    const r = parseResponse("HTTP/1.1 204 No Content\r\nContent-Length: 0\r\n\r\n");
    expect(responseContentLength(r)).toBe(0);
  });

  it("content-length uses byte length of body when present", () => {
    const r = parseResponse("HTTP/1.1 200 OK\r\n\r\nhello");
    expect(responseContentLength(r)).toBe(5);
  });
});

describe("statusClass", () => {
  it("classifies common ranges", () => {
    expect(statusClass(200)).toBe("2xx");
    expect(statusClass(404)).toBe("4xx");
    expect(statusClass(500)).toBe("5xx");
    expect(statusClass(0)).toBe("0xx");
  });
});
