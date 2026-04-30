// SSE client to Burp's official MCP server.
//
// Burp exposes proxy history through `get_proxy_http_history`, returning
// paginated results. Each result item is a JSON string of
// {"request": "...", "response": "...", "notes": "..."}.
//
// We reconnect lazily — there's no benefit to keeping the SSE pipe open
// between infrequent refreshes. Each fetch(offset) call opens one session,
// pages through Burp's history starting at offset, and closes the session.

import * as fs from "node:fs/promises";
import { Client } from "@modelcontextprotocol/sdk/client/index.js";
import { SSEClientTransport } from "@modelcontextprotocol/sdk/client/sse.js";
import type { CallToolResult } from "@modelcontextprotocol/sdk/types.js";

import type { RawEntry } from "./snapshot.js";

// A single page we ask Burp for. Empirically 20 is reliable on real Burp
// histories; larger pages cause the official server to close the SSE pipe
// mid-fetch on entries with very long Cookie headers.
export const DEFAULT_PAGE = 20;

const TRUNCATION_MARKER = "... (truncated)";

export class UpstreamDeniedError extends Error {
  constructor(message: string) {
    super(message);
    this.name = "UpstreamDeniedError";
  }
}

export interface Upstream {
  fetch(offset?: number): Promise<RawEntry[]>;
}

export class BurpUpstream implements Upstream {
  readonly url: URL;
  readonly pageSize: number;
  readonly maxEntries: number;

  constructor(opts: {
    url?: string;
    pageSize?: number;
    maxEntries?: number;
  } = {}) {
    this.url = new URL(opts.url ?? "http://127.0.0.1:9876/");
    this.pageSize = opts.pageSize ?? DEFAULT_PAGE;
    this.maxEntries = opts.maxEntries ?? 5000;
  }

  async fetch(offset = 0): Promise<RawEntry[]> {
    const out: RawEntry[] = [];
    const cap = this.maxEntries;
    const transport = new SSEClientTransport(this.url);
    const client = new Client(
      { name: "burp-mcp-lite-upstream", version: "0.2.0" },
      { capabilities: {} },
    );
    try {
      await client.connect(transport);
      let curOffset = offset;
      while (out.length < cap) {
        const count = Math.min(this.pageSize, cap - out.length);
        const result = (await client.callTool({
          name: "get_proxy_http_history",
          arguments: { count, offset: curOffset },
        })) as CallToolResult;
        const page = decodeHistoryResult(result);
        if (page.length === 0) break;
        out.push(...page);
        if (page.length < count) break;
        curOffset += page.length;
      }
    } finally {
      await client.close().catch(() => undefined);
    }
    return out;
  }
}

export class FixtureUpstream implements Upstream {
  readonly path: string;

  constructor(path: string) {
    this.path = path;
  }

  async fetch(offset = 0): Promise<RawEntry[]> {
    const text = await fs.readFile(this.path, "utf-8");
    const data = JSON.parse(text) as Array<{
      request?: string;
      response?: string;
      notes?: string | null;
    }>;
    const all: RawEntry[] = data.map((obj) => ({
      request: obj.request ?? "",
      response: obj.response ?? "",
      notes: obj.notes ?? null,
    }));
    return all.slice(offset);
  }
}

export function decodeHistoryResult(result: CallToolResult): RawEntry[] {
  const entries: RawEntry[] = [];
  const contents = result.content ?? [];
  for (const c of contents) {
    if (c.type !== "text") continue;
    const text = (c as { text?: string }).text ?? "";
    if (!text) continue;
    if (text === "Reached end of items") continue;
    if (text.startsWith("HTTP history access denied")) {
      throw new UpstreamDeniedError(text);
    }
    for (const piece of text.split("\n\n")) {
      const trimmed = piece.trim();
      if (!trimmed) continue;
      const entry = decodeOne(trimmed);
      if (entry) entries.push(entry);
    }
  }
  return entries;
}

function decodeOne(piece: string): RawEntry | null {
  // Try strict JSON first.
  try {
    const obj = JSON.parse(piece) as {
      request?: string;
      response?: string;
      notes?: string | null;
    };
    return {
      request: obj.request ?? "",
      response: obj.response ?? "",
      notes: obj.notes ?? null,
    };
  } catch {
    // fall through to salvage path
  }

  // Salvage: drop the truncation marker, then pull request/response/notes
  // string values out by manual scan. Burp's Tools.kt caps each entry at
  // 5000 chars; if truncation cut us mid-string-mid-escape, the JSON parse
  // above failed but we can still recover the partial values.
  let body = piece;
  if (body.endsWith(TRUNCATION_MARKER)) {
    body = body.slice(0, -TRUNCATION_MARKER.length);
  }

  const req = extractJsonString(body, '"request":"');
  const resp = extractJsonString(body, '"response":"');
  const notes = extractJsonString(body, '"notes":"');
  if (req === null && resp === null) return null;
  return {
    request: req ?? "",
    response: resp ?? "",
    notes,
  };
}

function extractJsonString(text: string, key: string): string | null {
  const idx = text.indexOf(key);
  if (idx === -1) return null;
  let i = idx + key.length;
  const out: string[] = [];
  const simple: Record<string, string> = {
    n: "\n",
    r: "\r",
    t: "\t",
    b: "\b",
    f: "\f",
    '"': '"',
    "\\": "\\",
    "/": "/",
  };
  while (i < text.length) {
    const ch = text[i]!;
    if (ch === '"') return out.join("");
    if (ch === "\\") {
      if (i + 1 >= text.length) return out.join("");
      const esc = text[i + 1]!;
      if (esc in simple) {
        out.push(simple[esc]!);
        i += 2;
        continue;
      }
      if (esc === "u") {
        if (i + 6 > text.length) return out.join("");
        const cp = Number.parseInt(text.slice(i + 2, i + 6), 16);
        if (!Number.isFinite(cp)) return out.join("");
        out.push(String.fromCharCode(cp));
        i += 6;
        continue;
      }
      out.push(ch);
      i += 1;
      continue;
    }
    out.push(ch);
    i += 1;
  }
  return out.join("");
}
