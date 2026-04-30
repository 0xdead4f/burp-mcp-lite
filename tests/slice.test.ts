import { describe, it, expect } from "vitest";
import {
  SNIPPET_LINE_CAP,
  sliceBody,
  windowedLine,
} from "../src/format/slice.js";

const BODY = Array.from({ length: 10 }, (_, i) => `line${i + 1}`).join("\n");

describe("sliceBody — basic specs", () => {
  it("full returns whole body", () => {
    const r = sliceBody(BODY, "full");
    expect(r.text).toBe(BODY);
    expect(r.truncated).toBe(false);
    expect(r.totalLines).toBe(10);
  });

  it("none returns empty truncated", () => {
    const r = sliceBody(BODY, "none");
    expect(r.text).toBe("");
    expect(r.truncated).toBe(true);
  });

  it("head:N returns first N lines", () => {
    const r = sliceBody(BODY, "head:3");
    expect(r.text).toBe("line1\nline2\nline3");
    expect(r.truncated).toBe(true);
  });

  it("head with N >= total is not truncated", () => {
    const r = sliceBody(BODY, "head:100");
    expect(r.text).toBe(BODY);
    expect(r.truncated).toBe(false);
  });

  it("tail:N returns last N lines", () => {
    const r = sliceBody(BODY, "tail:2");
    expect(r.text).toBe("line9\nline10");
    expect(r.truncated).toBe(true);
  });

  it("regex with context shows surrounding lines", () => {
    const r = sliceBody(BODY, "/line5/", 1);
    expect(r.hitCount).toBe(1);
    expect(r.text).toContain("[L4] line4");
    expect(r.text).toContain("[L5] line5");
    expect(r.text).toContain("[L6] line6");
  });

  it("regex with no match returns <no matches>", () => {
    const r = sliceBody(BODY, "/zzzzz/");
    expect(r.text).toBe("<no matches>");
    expect(r.hitCount).toBe(0);
  });

  it("regex with invalid pattern is reported in text", () => {
    const r = sliceBody(BODY, "/[/");
    expect(r.text).toContain("invalid regex");
  });

  it("regex with non-adjacent hits inserts gap separator", () => {
    const body = ["a", "b", "FIND_ME", "c", "d", "e", "f", "FIND_ME", "g"].join("\n");
    const r = sliceBody(body, "/FIND_ME/", 0);
    expect((r.text.match(/\.\.\./g) ?? []).length).toBe(1);
    expect(r.hitCount).toBe(2);
  });

  it("empty body returns empty result", () => {
    const r = sliceBody("", "full");
    expect(r.text).toBe("");
    expect(r.totalLines).toBe(0);
  });
});

describe("windowedLine", () => {
  it("passes through short lines", () => {
    expect(windowedLine("short value", /value/)).toBe("short value");
  });

  it("centers a window on the match", () => {
    const line = "x".repeat(500) + "TARGET" + "y".repeat(500);
    const out = windowedLine(line, /TARGET/, 80);
    expect(out).toContain("TARGET");
    expect(out.length).toBeLessThanOrEqual(82);
    expect(out.startsWith("…")).toBe(true);
    expect(out.endsWith("…")).toBe(true);
  });

  it("head-trims when no rx given", () => {
    const line = "a".repeat(500);
    const out = windowedLine(line, null, 50);
    expect(out.endsWith("…")).toBe(true);
    expect(out.length).toBe(50);
  });

  it("trims a match that itself exceeds the cap", () => {
    const line = "M" + "A".repeat(998) + "Z";
    const out = windowedLine(line, /M.*Z/, 40);
    // Match starts at 0, leading ellipsis omitted.
    expect(out.endsWith("…")).toBe(true);
    expect(out.length).toBe(40);
  });
});

describe("sliceBody regex with windowing", () => {
  it("caps a long matching line", () => {
    const longLine = "x".repeat(1000) + "FIND" + "y".repeat(1000);
    const body = `alpha\n${longLine}\nomega`;
    const r = sliceBody(body, "/FIND/", 0);
    expect(r.hitCount).toBe(1);
    const hitLines = r.text.split("\n").filter((l) => l.includes("FIND"));
    expect(hitLines.length).toBeGreaterThan(0);
    expect(hitLines[0]!.length).toBeLessThanOrEqual(SNIPPET_LINE_CAP + 16);
    expect(r.truncated).toBe(true);
  });

  it("leaves short lines untouched", () => {
    const body = ["a", "b", "FIND_ME", "c", "d"].join("\n");
    const r = sliceBody(body, "/FIND_ME/", 1);
    expect(r.text).toContain("[L3] FIND_ME");
    expect(r.text).toContain("[L2] b");
    expect(r.text).toContain("[L4] c");
  });
});
