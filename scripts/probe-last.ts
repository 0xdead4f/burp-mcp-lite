// One-shot probe: call Burp's get_proxy_http_history directly with count=1.
// Bypasses the snapshot/full-fetch pipeline so we can confirm the upstream
// channel works without tripping the "SSE closes mid-page on long entries"
// issue. Shows the raw upstream payload shape.
//
// Usage:
//   npx tsx scripts/probe-last.ts [--offset N]

import { Client } from "@modelcontextprotocol/sdk/client/index.js";
import { SSEClientTransport } from "@modelcontextprotocol/sdk/client/sse.js";
import type { CallToolResult } from "@modelcontextprotocol/sdk/types.js";

import { decodeHistoryResult } from "../src/upstream.js";

const CALL_TIMEOUT_MS = 8_000;

function parseArgs(argv: string[]): { url: string; offset: number | null } {
  const args = argv.slice(2);
  let url = "http://127.0.0.1:9876/";
  let offset: number | null = null;
  for (let i = 0; i < args.length; i++) {
    if (args[i] === "--url") url = args[++i] ?? url;
    else if (args[i] === "--offset") offset = Number(args[++i] ?? "");
  }
  return { url, offset };
}

async function probe(client: Client, offset: number): Promise<string | null> {
  // Per-fetch timeout. A timeout usually means the upstream truncated
  // mid-message and the SSE reader is stuck. Treat as past-end.
  const ctrl = new AbortController();
  const timer = setTimeout(() => ctrl.abort(), CALL_TIMEOUT_MS);
  try {
    const result = (await client.callTool({
      name: "get_proxy_http_history",
      arguments: { count: 1, offset },
    })) as CallToolResult;
    let text = "";
    for (const c of result.content ?? []) {
      if (c.type === "text") text += (c as { text?: string }).text ?? "";
    }
    return text;
  } catch {
    return null;
  } finally {
    clearTimeout(timer);
  }
}

async function findLastOffset(client: Client): Promise<number> {
  let lastGood = 0;
  let hi = 1;
  while (hi <= 1_000_000) {
    const text = await probe(client, hi);
    const status =
      text === null
        ? "timeout"
        : text.includes("Reached end of items")
        ? "end"
        : "ok";
    process.stdout.write(`  probe offset=${hi}: ${status}\n`);
    if (text === null) break;
    if (text.includes("Reached end of items")) break;
    lastGood = hi;
    hi *= 2;
  }
  if (hi <= lastGood) return lastGood;

  let lo = lastGood;
  while (lo + 1 < hi) {
    const mid = Math.floor((lo + hi) / 2);
    const text = await probe(client, mid);
    const status =
      text === null
        ? "timeout"
        : text.includes("Reached end of items")
        ? "end"
        : "ok";
    process.stdout.write(`  bisect mid=${mid}: ${status}\n`);
    if (text === null || text.includes("Reached end of items")) hi = mid;
    else lo = mid;
  }
  return lo;
}

async function main(): Promise<number> {
  const { url, offset } = parseArgs(process.argv);
  const transport = new SSEClientTransport(new URL(url));
  const client = new Client({ name: "burp-mcp-lite-probe", version: "0.2.0" });
  await client.connect(transport);

  try {
    let off = offset;
    if (off === null) {
      process.stdout.write("[probe] searching for last offset…\n");
      off = await findLastOffset(client);
      process.stdout.write(`[probe] last offset = ${off}\n`);
    }

    const result = (await client.callTool({
      name: "get_proxy_http_history",
      arguments: { count: 1, offset: off },
    })) as CallToolResult;

    const entries = decodeHistoryResult(result);
    process.stdout.write(
      `[probe] decoded ${entries.length} entry/entries from offset=${off}\n`,
    );
    for (let i = 0; i < entries.length; i++) {
      const { request, response, notes } = entries[i]!;
      process.stdout.write(`\n=== entry @ offset ${off + i} ===\n`);
      process.stdout.write(`-- request (${request.length} chars) --\n`);
      process.stdout.write(request.slice(0, 1500));
      if (request.length > 1500) {
        process.stdout.write(`\n... (+${request.length - 1500} more chars)\n`);
      } else {
        process.stdout.write("\n");
      }
      process.stdout.write(`-- response (${response.length} chars) --\n`);
      process.stdout.write(response.slice(0, 1500));
      if (response.length > 1500) {
        process.stdout.write(`\n... (+${response.length - 1500} more chars)\n`);
      } else {
        process.stdout.write("\n");
      }
      process.stdout.write(`-- notes: ${JSON.stringify(notes)}\n`);
    }
  } finally {
    await client.close().catch(() => undefined);
  }
  return 0;
}

main().then(
  (code) => process.exit(code),
  (err) => {
    process.stderr.write(`probe failed: ${(err as Error).stack ?? err}\n`);
    process.exit(1);
  },
);
