// Filter helpers: status range parsing, host/path/method/mime predicates.

import type { HistoryEntry } from "./snapshot.js";

export type StatusPredicate = (status: number) => boolean;

export type MatchTarget =
  | "request.body"
  | "request.headers"
  | "request.all"
  | "response.body"
  | "response.headers"
  | "response.all";

const STATUS_TOKEN_RX = /^([1-5])xx$/;

function expandLow(tok: string): number {
  const t = tok.trim().toLowerCase();
  const m = STATUS_TOKEN_RX.exec(t);
  if (m) return Number.parseInt(m[1]!, 10) * 100;
  return Number.parseInt(t, 10);
}

function expandHigh(tok: string): number {
  const t = tok.trim().toLowerCase();
  const m = STATUS_TOKEN_RX.exec(t);
  if (m) return Number.parseInt(m[1]!, 10) * 100 + 99;
  return Number.parseInt(t, 10);
}

export function parseStatusFilter(spec: string): StatusPredicate {
  const matchers: StatusPredicate[] = [];
  for (const rawTok of spec.split(",")) {
    const token = rawTok.trim().toLowerCase();
    if (!token) continue;

    if (token.includes("-")) {
      const [loS, hiS] = token.split("-", 2) as [string, string];
      const lo = expandLow(loS);
      const hi = expandHigh(hiS);
      if (Number.isFinite(lo) && Number.isFinite(hi)) {
        matchers.push((s) => s >= lo && s <= hi);
      } else {
        matchers.push(() => false);
      }
      continue;
    }

    const cls = STATUS_TOKEN_RX.exec(token);
    if (cls) {
      const c = Number.parseInt(cls[1]!, 10);
      matchers.push((s) => s >= c * 100 && s <= c * 100 + 99);
      continue;
    }

    const exact = Number.parseInt(token, 10);
    if (Number.isFinite(exact)) {
      matchers.push((s) => s === exact);
    } else {
      // Unknown token: treat as never-match so the filter doesn't silently
      // pass everything.
      matchers.push(() => false);
    }
  }
  if (matchers.length === 0) return () => true;
  return (s) => matchers.some((m) => m(s));
}

function normalizeMethods(
  m: string | readonly string[] | undefined,
): Set<string> | undefined {
  if (m === undefined) return undefined;
  const items =
    typeof m === "string"
      ? m.split(",").map((x) => x.trim()).filter(Boolean)
      : m.map((x) => x.trim()).filter(Boolean);
  if (items.length === 0) return undefined;
  return new Set(items.map((x) => x.toUpperCase()));
}

function renderHeaders(headers: readonly (readonly [string, string])[]): string {
  return headers.map(([k, v]) => `${k}: ${v}`).join("\n");
}

export function resolveMatchTarget(e: HistoryEntry, target: string): string {
  switch (target.trim().toLowerCase()) {
    case "response.body":
      return e.response.body;
    case "response.headers":
      return renderHeaders(e.response.headers);
    case "response.all":
      return renderHeaders(e.response.headers) + "\n\n" + e.response.body;
    case "request.body":
      return e.request.body;
    case "request.headers":
      return renderHeaders(e.request.headers);
    case "request.all":
      return renderHeaders(e.request.headers) + "\n\n" + e.request.body;
    default:
      return e.response.body;
  }
}

export interface BuildFilterArgs {
  host?: string;
  path?: string;
  method?: string | readonly string[];
  status?: string;
  mime?: string;
  match?: string;
  matchIn?: string;
  caseSensitive?: boolean;
}

export interface HistoryFilter {
  matches(e: HistoryEntry): boolean;
}

export function buildFilter(args: BuildFilterArgs = {}): HistoryFilter {
  const flags = args.caseSensitive ? "" : "i";
  const hostNeedle = args.host ? args.host.toLowerCase() : undefined;
  const pathRx = args.path ? new RegExp(args.path, flags) : undefined;
  const methods = normalizeMethods(args.method);
  const statusPred = args.status ? parseStatusFilter(args.status) : undefined;
  const mimeNeedle = args.mime ? args.mime.toLowerCase() : undefined;
  const matchRx = args.match ? new RegExp(args.match, flags) : undefined;
  const matchIn = args.matchIn ?? "response.body";

  return {
    matches(e) {
      if (methods && !methods.has((e.request.method || "").toUpperCase())) {
        return false;
      }
      if (hostNeedle) {
        if (!(e.request.host || "").toLowerCase().includes(hostNeedle)) {
          return false;
        }
      }
      if (pathRx && !pathRx.test(e.request.path || "")) return false;
      if (statusPred && !statusPred(e.response.status)) return false;
      if (mimeNeedle) {
        if (!(e.response.contentType || "").toLowerCase().includes(mimeNeedle)) {
          return false;
        }
      }
      if (matchRx) {
        const target = resolveMatchTarget(e, matchIn);
        if (!matchRx.test(target)) return false;
      }
      return true;
    },
  };
}

export function applyFilter(
  entries: readonly HistoryEntry[],
  f: HistoryFilter,
): HistoryEntry[] {
  return entries.filter((e) => f.matches(e));
}
