// Smoke test: spawn the GLOBALLY-INSTALLED `burp-mcp-lite` binary against the
// shipped fixture, list tools, and call list_history + match. Confirms the
// published artifact (tarball → npm install -g) is wire-correct.

import * as path from "node:path";
import { Client } from "@modelcontextprotocol/sdk/client/index.js";
import { StdioClientTransport } from "@modelcontextprotocol/sdk/client/stdio.js";
import type { CallToolResult } from "@modelcontextprotocol/sdk/types.js";

const FIXTURE = path.resolve(
  process.cwd(),
  "tests/fixtures/sample.json",
);

function asText(r: CallToolResult): string {
  const c = r.content?.[0];
  if (!c || c.type !== "text") return "";
  return (c as { text?: string }).text ?? "";
}

async function main() {
  const transport = new StdioClientTransport({
    command: "burp-mcp-lite",
    args: ["--fixture", FIXTURE],
  });
  const client = new Client({ name: "smoke", version: "0.0.0" });

  await client.connect(transport);
  try {
    const tools = await client.listTools();
    console.log(`tools: ${tools.tools.map((t) => t.name).join(", ")}`);

    const list = (await client.callTool({
      name: "list_history",
      arguments: {},
    })) as CallToolResult;
    const listText = asText(list);
    console.log("---list_history---");
    console.log(listText);

    const m = (await client.callTool({
      name: "match",
      arguments: { id: 3, pattern: "token" },
    })) as CallToolResult;
    console.log("---match id=3 'token'---");
    console.log(asText(m));

    const stats = (await client.callTool({
      name: "stats",
      arguments: {},
    })) as CallToolResult;
    console.log("---stats---");
    console.log(asText(stats));
  } finally {
    await client.close().catch(() => undefined);
  }
}

main().catch((e) => {
  console.error("smoke failed:", e);
  process.exit(1);
});
