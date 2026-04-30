// Text rendering for tool outputs. Single source of truth for column widths,
// headers, footers, and JSON variants. Tuned for token efficiency.

import type { HistoryEntry } from "../snapshot.js";
import { entryTimestamp } from "../snapshot.js";

export type Field = "id" | "method" | "status" | "host" | "path" | "len" | "mime" | "time";

export const DEFAULT_FIELDS: readonly Field[] = ["id", "method", "status", "host", "path", "len"];
export const ALL_FIELDS: ReadonlySet<Field> = new Set([
  "id",
  "method",
  "status",
  "host",
  "path",
  "len",
  "mime",
  "time",
]);

const COLUMN_CAP: Partial<Record<Field, number>> = {
  host: 32,
  path: 60,
  mime: 24,
};

function pad2(n: number): string {
  return n < 10 ? `0${n}` : String(n);
}

export function formatEntryTime(e: HistoryEntry): string {
  // HH:MM:SS for today, MM-DD HH:MM:SS otherwise. Local time.
  const dt = entryTimestamp(e);
  const today = new Date();
  const sameDay =
    dt.getFullYear() === today.getFullYear() &&
    dt.getMonth() === today.getMonth() &&
    dt.getDate() === today.getDate();
  const hms = `${pad2(dt.getHours())}:${pad2(dt.getMinutes())}:${pad2(dt.getSeconds())}`;
  if (sameDay) return hms;
  return `${pad2(dt.getMonth() + 1)}-${pad2(dt.getDate())} ${hms}`;
}

function humanSize(n: number): string {
  if (n <= 0) return "0";
  if (n < 1024) return String(n);
  if (n < 1024 * 1024) return `${(n / 1024).toFixed(1)}K`;
  return `${(n / (1024 * 1024)).toFixed(1)}M`;
}

function entryField(e: HistoryEntry, field: Field): string {
  switch (field) {
    case "id":
      return String(e.id);
    case "method":
      return e.request.method || "-";
    case "status":
      return e.response.status ? String(e.response.status) : "-";
    case "host":
      return e.request.host || "-";
    case "path":
      return e.request.path || "-";
    case "len":
      return humanSize(e.response.contentLength);
    case "mime":
      return e.response.contentType || "-";
    case "time":
      return formatEntryTime(e);
  }
}

function ndjsonField(e: HistoryEntry, field: Field): unknown {
  switch (field) {
    case "id":
      return e.id;
    case "status":
      return e.response.status;
    case "len":
      return e.response.contentLength;
    case "method":
      return e.request.method;
    case "host":
      return e.request.host;
    case "path":
      return e.request.path;
    case "mime":
      return e.response.contentType;
    case "time":
      return entryTimestamp(e).toISOString().replace(/\.\d{3}Z$/, "Z");
    default:
      return null;
  }
}

function truncate(s: string, n: number): string {
  if (s.length <= n) return s;
  if (n <= 1) return "…";
  return s.slice(0, n - 1) + "…";
}

export function renderHistoryTable(
  entries: readonly HistoryEntry[],
  fields: readonly Field[],
  total: number,
  offset: number,
): string {
  const cells: string[][] = entries.map((e) =>
    fields.map((f) => truncate(entryField(e, f), COLUMN_CAP[f] ?? 64)),
  );

  const widths = fields.map((f, i) => {
    const colMax = Math.max(
      f.length,
      ...cells.map((row) => row[i]!.length),
    );
    return colMax;
  });

  const lines: string[] = [];
  const header = fields
    .map((f, i) => f.padEnd(widths[i]!))
    .join("  ")
    .trimEnd();
  lines.push(header);
  for (const row of cells) {
    lines.push(
      row
        .map((c, i) => c.padEnd(widths[i]!))
        .join("  ")
        .trimEnd(),
    );
  }
  lines.push(`-- ${entries.length} of ${total} (offset ${offset}) --`);
  return lines.join("\n");
}

export function renderHistoryNdjson(
  entries: readonly HistoryEntry[],
  fields: readonly Field[],
): string {
  const lines: string[] = [];
  for (const e of entries) {
    const obj: Record<string, unknown> = {};
    for (const f of fields) obj[f] = ndjsonField(e, f);
    lines.push(JSON.stringify(obj));
  }
  return lines.join("\n");
}

function schemeGuess(_e: HistoryEntry): string {
  // Burp's serializer drops scheme. Most proxy traffic is https; better
  // hardcode than a misleading "?" placeholder.
  return "https";
}

export function renderRequestView(args: {
  e: HistoryEntry;
  headers: readonly (readonly [string, string])[];
  body: string;
  showHeaders: boolean;
  note?: string;
}): string {
  const { e, headers, body, showHeaders, note } = args;
  const parts: string[] = [];
  const scheme = schemeGuess(e);
  const line = `[${e.id}] ${e.request.method} ${scheme}://${e.request.host}${e.request.path}  (${formatEntryTime(e)})`;
  parts.push(line);
  if (showHeaders && headers.length) {
    parts.push(headers.map(([k, v]) => `${k}: ${v}`).join("\n"));
  }
  parts.push(""); // blank line before body
  parts.push(body || "(no body)");
  if (note) parts.push(note);
  return parts.join("\n");
}

export function renderResponseView(args: {
  e: HistoryEntry;
  headers: readonly (readonly [string, string])[];
  body: string;
  showHeaders: boolean;
  note?: string;
}): string {
  const { e, headers, body, showHeaders, note } = args;
  const size = humanSize(e.response.contentLength);
  const ct = e.response.contentType || "?";
  const head = `[${e.id}] ${e.response.status} ${e.response.reason}`.trimEnd() +
    `  (${size}, ${ct}, ${formatEntryTime(e)})`;
  const parts: string[] = [head];
  if (showHeaders && headers.length) {
    parts.push(headers.map(([k, v]) => `${k}: ${v}`).join("\n"));
  }
  parts.push("");
  parts.push(body || "(no body)");
  if (note) parts.push(note);
  return parts.join("\n");
}

export function renderMatch(args: {
  matched: boolean;
  target: string;
  hits: number;
  snippets: readonly string[];
}): string {
  const { matched, target, hits, snippets } = args;
  if (!matched) return `matched: false\ntarget: ${target}`;
  const head = `matched: true\ntarget: ${target}\nhits: ${hits}`;
  if (snippets.length === 0) return head;
  return head + "\n" + snippets.join("\n");
}

export function renderEndpoints(
  rows: readonly (readonly [string, string, string, number])[],
): string {
  if (rows.length === 0) return "(no endpoints)";
  const methodW = Math.max(...rows.map((r) => r[0].length));
  const hostW = Math.min(48, Math.max(...rows.map((r) => r[1].length)));
  const pathW = Math.min(64, Math.max(...rows.map((r) => r[2].length)));
  return rows
    .map(([method, host, path, count]) => {
      return (
        method.padEnd(methodW) +
        "  " +
        truncate(host, hostW).padEnd(hostW) +
        "  " +
        truncate(path, pathW).padEnd(pathW) +
        `  ×${count}`
      );
    })
    .join("\n");
}

export function renderStats(args: {
  total: number;
  byMethod: Record<string, number>;
  byClass: Record<string, number>;
  byHost: readonly (readonly [string, number])[];
}): string {
  const { total, byMethod, byClass, byHost } = args;
  const lines = [`total entries: ${total}`];
  if (Object.keys(byMethod).length) {
    const items = Object.keys(byMethod)
      .sort()
      .map((k) => `${k}=${byMethod[k]}`);
    lines.push("by method: " + items.join(", "));
  }
  if (Object.keys(byClass).length) {
    const items = Object.keys(byClass)
      .sort()
      .map((k) => `${k}=${byClass[k]}`);
    lines.push("by status: " + items.join(", "));
  }
  if (byHost.length) {
    lines.push("top hosts:");
    for (const [host, n] of byHost) lines.push(`  ${host}  ×${n}`);
  }
  return lines.join("\n");
}

export function errorLine(message: string): string {
  return `error: ${message}`;
}
