// MCP stdio server. Registers the six tools and routes calls into the tool
// implementations. Tool descriptions are kept compact — they're paid for in
// the LLM's tool-schema budget on every turn.

import { McpServer } from "@modelcontextprotocol/sdk/server/mcp.js";
import { StdioServerTransport } from "@modelcontextprotocol/sdk/server/stdio.js";
import { z } from "zod";

import type { SnapshotStore } from "./snapshot.js";
import { listHistory } from "./tools/list-history.js";
import { viewRequest, viewResponse } from "./tools/view.js";
import { match } from "./tools/match.js";
import { endpoints } from "./tools/endpoints.js";
import { stats } from "./tools/stats.js";

const matchTargetEnum = z.enum([
  "request.body",
  "request.headers",
  "request.all",
  "response.body",
  "response.headers",
  "response.all",
]);

const fieldEnum = z.enum([
  "id",
  "method",
  "status",
  "host",
  "path",
  "len",
  "mime",
  "time",
]);

const methodArg = z.union([z.string(), z.array(z.string())]).optional();

export function buildServer(store: SnapshotStore, name = "burp-mcp-lite"): McpServer {
  const server = new McpServer(
    { name, version: "0.2.0" },
    {
      instructions:
        "Token-efficient Burp proxy history viewer. Default outputs are " +
        "headerless and redacted — opt in when you need raw bytes.",
    },
  );

  server.registerTool(
    "list_history",
    {
      description:
        "Browse Burp proxy history with field projection and filters. " +
        "Returns a compact text table by default. Use this for Recon " +
        "(what was captured?), Filter (only POSTs that 4xx'd), and " +
        "Cross-search (regex over response bodies via match=).",
      inputSchema: {
        limit: z.number().int().min(0).optional(),
        offset: z.number().int().min(0).optional(),
        fields: z.array(fieldEnum).optional(),
        host: z.string().optional(),
        path: z.string().optional(),
        method: methodArg,
        status: z.string().optional(),
        mime: z.string().optional(),
        match: z.string().optional(),
        match_in: matchTargetEnum.optional(),
        order: z.enum(["latest", "oldest"]).optional(),
        format: z.enum(["text", "json"]).optional(),
        refresh: z.boolean().optional(),
      },
    },
    async (args) => {
      try {
        const text = await listHistory(store, {
          limit: args.limit,
          offset: args.offset,
          fields: args.fields,
          host: args.host,
          path: args.path,
          method: args.method,
          status: args.status,
          mime: args.mime,
          match: args.match,
          matchIn: args.match_in,
          order: args.order,
          format: args.format,
          refresh: args.refresh,
        });
        return { content: [{ type: "text", text }] };
      } catch (e) {
        return { content: [{ type: "text", text: errString(e) }] };
      }
    },
  );

  server.registerTool(
    "view_request",
    {
      description:
        "View one request by id (from list_history). Headers and " +
        "cookies are OFF by default — pass include_headers=true if you " +
        "need them. Auth header values are redacted by default; pass " +
        'redact=false for the raw bytes. Body slicing: "full", "none", ' +
        '"head:N", "tail:N", or "/regex/".',
      inputSchema: {
        id: z.number().int(),
        include_headers: z.boolean().optional(),
        include_cookies: z.boolean().optional(),
        redact: z.boolean().optional(),
        body: z.string().optional(),
        context: z.number().int().optional(),
      },
    },
    async (args) => {
      try {
        const text = await viewRequest(store, {
          id: args.id,
          includeHeaders: args.include_headers,
          includeCookies: args.include_cookies,
          redact: args.redact,
          body: args.body,
          context: args.context,
        });
        return { content: [{ type: "text", text }] };
      } catch (e) {
        return { content: [{ type: "text", text: errString(e) }] };
      }
    },
  );

  server.registerTool(
    "view_response",
    {
      description:
        "View one response by id. Same toggles as view_request. Default " +
        'body="auto" prints full body if <4KB, else head:20 + a marker. ' +
        'Pass body="full" to override.',
      inputSchema: {
        id: z.number().int(),
        include_headers: z.boolean().optional(),
        include_set_cookie: z.boolean().optional(),
        redact: z.boolean().optional(),
        body: z.string().optional(),
        context: z.number().int().optional(),
      },
    },
    async (args) => {
      try {
        const text = await viewResponse(store, {
          id: args.id,
          includeHeaders: args.include_headers,
          includeSetCookie: args.include_set_cookie,
          redact: args.redact,
          body: args.body,
          context: args.context,
        });
        return { content: [{ type: "text", text }] };
      } catch (e) {
        return { content: [{ type: "text", text: errString(e) }] };
      }
    },
  );

  server.registerTool(
    "match",
    {
      description:
        "Predicate: does the entry match a regex? Returns " +
        '"matched: true/false" plus a small evidence snippet — never ' +
        "the full body. Use this when you want to verify presence of " +
        "a token/value without dumping the response into context.",
      inputSchema: {
        id: z.number().int(),
        pattern: z.string(),
        target: matchTargetEnum.optional(),
        case_sensitive: z.boolean().optional(),
        context: z.number().int().optional(),
        max_hits: z.number().int().optional(),
      },
    },
    async (args) => {
      try {
        const text = await match(store, {
          id: args.id,
          pattern: args.pattern,
          target: args.target,
          caseSensitive: args.case_sensitive,
          context: args.context,
          maxHits: args.max_hits,
        });
        return { content: [{ type: "text", text }] };
      } catch (e) {
        return { content: [{ type: "text", text: errString(e) }] };
      }
    },
  );

  server.registerTool(
    "endpoints",
    {
      description:
        "Deduplicated method+host+path inventory across history, with " +
        "hit counts. Query strings are stripped for dedup. Filters: " +
        "host, path, method.",
      inputSchema: {
        host: z.string().optional(),
        path: z.string().optional(),
        method: methodArg,
      },
    },
    async (args) => {
      try {
        const text = await endpoints(store, {
          host: args.host,
          path: args.path,
          method: args.method,
        });
        return { content: [{ type: "text", text }] };
      } catch (e) {
        return { content: [{ type: "text", text: errString(e) }] };
      }
    },
  );

  server.registerTool(
    "stats",
    {
      description:
        "Counts by method, status class, and top hosts across the cached snapshot.",
      inputSchema: {},
    },
    async () => {
      try {
        const text = await stats(store);
        return { content: [{ type: "text", text }] };
      } catch (e) {
        return { content: [{ type: "text", text: errString(e) }] };
      }
    },
  );

  return server;
}

function errString(e: unknown): string {
  const err = e as Error;
  return `error: ${err.name ?? "Error"}: ${err.message ?? String(e)}`;
}

export async function serveStdio(store: SnapshotStore): Promise<void> {
  const server = buildServer(store);
  const transport = new StdioServerTransport();
  await server.connect(transport);
}
