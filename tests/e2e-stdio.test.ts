// End-to-end test: spawn the burp-mcp-lite server as a stdio child process,
// talk to it as a real MCP client, and exercise every tool against the
// fixture. This is the strongest correctness signal we have without a live
// Burp instance.

import * as path from "node:path";
import { fileURLToPath } from "node:url";
import { describe, it, expect } from "vitest";
import { Client } from "@modelcontextprotocol/sdk/client/index.js";
import { StdioClientTransport } from "@modelcontextprotocol/sdk/client/stdio.js";
import type { CallToolResult } from "@modelcontextprotocol/sdk/types.js";

const HERE = path.dirname(fileURLToPath(import.meta.url));
const PROJECT_ROOT = path.resolve(HERE, "..");
const FIXTURE = path.join(HERE, "fixtures", "sample.json");

function asText(result: CallToolResult): string {
  const c = result.content?.[0];
  if (!c || c.type !== "text") return "";
  return (c as { text?: string }).text ?? "";
}

describe("e2e stdio (full workflow)", () => {
  it("lists tools and exercises every one", async () => {
    const transport = new StdioClientTransport({
      command: "npx",
      args: ["-y", "tsx", path.join(PROJECT_ROOT, "src", "cli.ts"), "--fixture", FIXTURE],
      cwd: PROJECT_ROOT,
    });
    const client = new Client({ name: "e2e-test", version: "0.0.0" });

    try {
      await client.connect(transport);

      const tools = await client.listTools();
      const names = new Set(tools.tools.map((t) => t.name));
      expect(names).toEqual(
        new Set([
          "list_history",
          "view_request",
          "view_response",
          "match",
          "endpoints",
          "stats",
        ]),
      );

      // 1) list_history default
      let r = (await client.callTool({ name: "list_history", arguments: {} })) as CallToolResult;
      expect(asText(r)).toContain("-- 4 of 4 (offset 0) --");

      // 2) filter
      r = (await client.callTool({
        name: "list_history",
        arguments: { method: "POST" },
      })) as CallToolResult;
      expect(asText(r)).toContain("-- 1 of 1");

      // 3) view_request — defaults hide auth
      r = (await client.callTool({ name: "view_request", arguments: { id: 0 } })) as CallToolResult;
      expect(asText(r)).not.toContain("Authorization");
      expect(asText(r)).toContain("[0] GET");

      // 4) view_request with redacted headers
      r = (await client.callTool({
        name: "view_request",
        arguments: { id: 0, include_headers: true },
      })) as CallToolResult;
      expect(asText(r)).toContain("Authorization: <redacted");
      expect(asText(r)).not.toContain("Bearer eyJ");

      // 5) match — predicate hit
      r = (await client.callTool({
        name: "match",
        arguments: { id: 3, pattern: "token" },
      })) as CallToolResult;
      expect(asText(r)).toContain("matched: true");

      // 6) match — predicate miss returns short answer
      r = (await client.callTool({
        name: "match",
        arguments: { id: 3, pattern: "nosuchsubstring" },
      })) as CallToolResult;
      expect(asText(r)).toBe("matched: false\ntarget: response.body");

      // 7) endpoints
      r = (await client.callTool({ name: "endpoints", arguments: {} })) as CallToolResult;
      expect(asText(r)).toContain("api.example.com");

      // 8) stats
      r = (await client.callTool({ name: "stats", arguments: {} })) as CallToolResult;
      expect(asText(r)).toContain("total entries: 4");
    } finally {
      await client.close().catch(() => undefined);
    }
  }, 60_000);
});
