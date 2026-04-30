import { describe, it, expect } from "vitest";
import { applyRedaction, redactValue } from "../src/format/redact.js";
import type { Header } from "../src/format/http-parse.js";

describe("redactValue", () => {
  it("redacts known auth headers with length stub", () => {
    expect(redactValue("Authorization", "Bearer eyJabc.def.ghi")).toBe(
      "<redacted 21c>",
    );
    expect(redactValue("authorization", "Bearer eyJabc.def.ghi")).toBe(
      "<redacted 21c>",
    );
    expect(redactValue("Cookie", "session=abc; HttpOnly")).toBe("<redacted 21c>");
    expect(redactValue("X-Api-Key", "secret")).toBe("<redacted 6c>");
  });

  it("returns placeholder when value is empty", () => {
    expect(redactValue("Authorization", "")).toBe("<redacted>");
  });

  it("passes through unrecognized header names unchanged", () => {
    expect(redactValue("Accept", "*/*")).toBe("*/*");
    expect(redactValue("Content-Type", "application/json")).toBe(
      "application/json",
    );
  });
});

describe("applyRedaction", () => {
  const headers: Header[] = [
    ["Host", "a"],
    ["Authorization", "Bearer xyz"],
    ["Content-Type", "application/json"],
  ];

  it("redacts when redact=true", () => {
    const out = applyRedaction(headers, true);
    expect(out.find(([k]) => k === "Authorization")?.[1]).toBe("<redacted 10c>");
    expect(out.find(([k]) => k === "Host")?.[1]).toBe("a");
  });

  it("returns headers unchanged when redact=false", () => {
    const out = applyRedaction(headers, false);
    expect(out.find(([k]) => k === "Authorization")?.[1]).toBe("Bearer xyz");
  });
});
