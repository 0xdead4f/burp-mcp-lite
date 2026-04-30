// CLI entry point: `burp-mcp-lite` (after `npm install -g`) or via npx.
//
// Modes:
//   default        — connect to Burp's MCP at http://127.0.0.1:9876/
//   --fixture FILE — read history from a JSON file (offline / dev / smoke)
//   --url URL      — override the upstream URL
//   --ttl SECONDS  — snapshot TTL before auto-refresh (default 30)

import { Command } from "commander";

import { serveStdio } from "./server.js";
import { SnapshotStore } from "./snapshot.js";
import { BurpUpstream, FixtureUpstream, type Upstream } from "./upstream.js";

interface CliOptions {
  url: string;
  fixture?: string;
  ttl: number;
  pageSize: number;
  maxEntries: number;
  verbose: boolean;
}

function buildStore(opts: CliOptions): SnapshotStore {
  const upstream: Upstream = opts.fixture
    ? new FixtureUpstream(opts.fixture)
    : new BurpUpstream({
        url: opts.url,
        pageSize: opts.pageSize,
        maxEntries: opts.maxEntries,
      });
  return new SnapshotStore((offset) => upstream.fetch(offset), opts.ttl);
}

export async function main(argv: string[] = process.argv): Promise<number> {
  const program = new Command();
  program
    .name("burp-mcp-lite")
    .description("Token-efficient middleware MCP server for Burp Suite proxy history.")
    .version("0.2.0")
    .option("--url <url>", "Burp MCP SSE URL", "http://127.0.0.1:9876/")
    .option("--fixture <file>", "Read history from a JSON fixture file (no Burp needed).")
    .option("--ttl <seconds>", "Snapshot cache TTL in seconds", (v) => Number(v), 30)
    .option("--page-size <n>", "Per-call page size when fetching from Burp", (v) => Number(v), 20)
    .option("--max-entries <n>", "Cap on history entries pulled per refresh", (v) => Number(v), 5000)
    .option("-v, --verbose", "Log to stderr", false);

  program.parse(argv);
  const o = program.opts<{
    url: string;
    fixture?: string;
    ttl: number;
    pageSize: number;
    maxEntries: number;
    verbose: boolean;
  }>();

  if (o.verbose) {
    // Minimal stderr logger; opt-in.
    process.stderr.write(
      `[burp-mcp-lite] starting (url=${o.url} fixture=${o.fixture ?? "-"} ttl=${o.ttl}s pageSize=${o.pageSize})\n`,
    );
  }

  const store = buildStore({
    url: o.url,
    fixture: o.fixture,
    ttl: o.ttl,
    pageSize: o.pageSize,
    maxEntries: o.maxEntries,
    verbose: o.verbose,
  });

  await serveStdio(store);
  return 0;
}

main().catch((e) => {
  process.stderr.write(`[burp-mcp-lite] fatal: ${(e as Error).stack ?? e}\n`);
  process.exit(1);
});
