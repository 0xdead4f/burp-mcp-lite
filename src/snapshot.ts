// In-memory history snapshot + stable id mapping.
//
// The Burp upstream gives us proxy history as an ordered list with no
// per-entry stable identifier. We assign session-local ids 0..N-1 in the
// order Burp returned them. New entries Burp captured after our last refresh
// keep their relative order and get appended ids — existing ids stay stable,
// which is exactly the contract `view_request(id=42)` relies on.
//
// Refresh policy is incremental:
//   1. Re-fetch starting at offset = N - 1 (last known index).
//   2. The first entry returned must equal our existing entry N-1 — anchor.
//      Everything after is new; append.
//   3. If anchor mismatches (history was cleared / replaced inside Burp),
//      fall back to a full rebuild.

import {
  type ParsedRequest,
  type ParsedResponse,
  parseRequest,
  parseResponse,
  findHeader,
  requestHost,
  requestPath,
  responseContentType,
  responseContentLength,
} from "./format/http-parse.js";

export interface RawEntry {
  request: string;
  response: string;
  notes: string | null;
}

export interface HistoryEntry {
  readonly id: number;
  readonly rawRequest: string;
  readonly rawResponse: string;
  readonly notes: string | null;
  readonly observedAt: number; // ms since epoch
  readonly request: ParsedRequest & { readonly host: string; readonly path: string };
  readonly response: ParsedResponse & {
    readonly contentType: string;
    readonly contentLength: number;
  };
}

function buildEntry(
  id: number,
  raw: RawEntry,
  observedAt: number,
): HistoryEntry {
  const req = parseRequest(raw.request || "");
  const resp = parseResponse(raw.response || "");
  return {
    id,
    rawRequest: raw.request || "",
    rawResponse: raw.response || "",
    notes: raw.notes ?? null,
    observedAt,
    request: { ...req, host: requestHost(req), path: requestPath(req) },
    response: {
      ...resp,
      contentType: responseContentType(resp),
      contentLength: responseContentLength(resp),
    },
  };
}

export function entryTimestamp(e: HistoryEntry): Date {
  // Prefer response Date header (per-entry, server clock); fall back to
  // observedAt (when our middleware ingested the entry).
  const dateHdr = findHeader(e.response.headers, "Date");
  if (dateHdr) {
    const t = Date.parse(dateHdr);
    if (!Number.isNaN(t)) return new Date(t);
  }
  return new Date(e.observedAt);
}

export interface Snapshot {
  readonly entries: readonly HistoryEntry[];
  readonly fetchedAt: number; // monotonic-ish; we use Date.now() — we only do deltas with it
  byId(id: number): HistoryEntry | undefined;
  readonly count: number;
}

function makeSnapshot(entries: readonly HistoryEntry[], fetchedAt: number): Snapshot {
  return {
    entries,
    fetchedAt,
    byId(id) {
      if (id >= 0 && id < entries.length) return entries[id];
      return undefined;
    },
    get count() {
      return entries.length;
    },
  };
}

export type Fetcher = (offset?: number) => Promise<RawEntry[]>;

export class SnapshotStore {
  private readonly fetcher: Fetcher;
  private readonly ttlMs: number;
  private snapshot: Snapshot | null = null;

  constructor(fetcher: Fetcher, ttlSeconds = 30) {
    this.fetcher = fetcher;
    this.ttlMs = ttlSeconds * 1000;
  }

  async get(refresh = false): Promise<Snapshot> {
    const now = Date.now();
    if (
      refresh ||
      this.snapshot === null ||
      now - this.snapshot.fetchedAt > this.ttlMs
    ) {
      await this.refresh();
    }
    return this.snapshot!;
  }

  private async refresh(): Promise<void> {
    const existing = this.snapshot?.entries ?? [];
    if (existing.length === 0) {
      await this.fullRebuild();
      return;
    }

    const anchorIdx = existing.length - 1;
    const page = await this.fetcher(anchorIdx);
    if (page.length === 0) {
      await this.fullRebuild();
      return;
    }

    const anchor = page[0]!;
    const last = existing[anchorIdx]!;
    if (anchor.request !== last.rawRequest || anchor.response !== last.rawResponse) {
      // History reordered or replaced; can't safely append.
      await this.fullRebuild();
      return;
    }

    const newRaws = page.slice(1);
    if (newRaws.length === 0) {
      this.snapshot = makeSnapshot(existing, Date.now());
      return;
    }
    const startId = existing.length;
    const observedAt = Date.now();
    const appended = newRaws.map((r, i) => buildEntry(startId + i, r, observedAt));
    this.snapshot = makeSnapshot([...existing, ...appended], observedAt);
  }

  private async fullRebuild(): Promise<void> {
    const raws = await this.fetcher(0);
    const observedAt = Date.now();
    const entries = raws.map((r, i) => buildEntry(i, r, observedAt));
    this.snapshot = makeSnapshot(entries, observedAt);
  }

  peek(): Snapshot | null {
    return this.snapshot;
  }
}
