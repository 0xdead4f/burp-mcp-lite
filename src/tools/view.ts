// view_request and view_response — single-entry inspection.
// Defaults: headers off, cookies off, redact on. Body slicing inherits the
// same DSL as match.

import { applyRedaction } from "../format/redact.js";
import { errorLine, renderRequestView, renderResponseView } from "../format/render.js";
import { sliceBody } from "../format/slice.js";
import type { SnapshotStore, HistoryEntry } from "../snapshot.js";

const AUTO_FULL_THRESHOLD = 4 * 1024;

function filterHeaders(
  headers: readonly (readonly [string, string])[],
  includeCookies: boolean,
  cookieName: string,
): (readonly [string, string])[] {
  if (includeCookies) return headers.slice();
  const lname = cookieName.toLowerCase();
  return headers.filter(([k]) => k.toLowerCase() !== lname);
}

function resolveBodySpec(spec: string, bodySize: number): string {
  if (spec.toLowerCase() === "auto") {
    return bodySize <= AUTO_FULL_THRESHOLD ? "full" : "head:20";
  }
  return spec;
}

function notFoundError(snapCount: number, id: number): string {
  return errorLine(
    `id ${id} not found in current history snapshot (have ids 0–${snapCount ? snapCount - 1 : -1})`,
  );
}

export interface ViewRequestArgs {
  id: number;
  includeHeaders?: boolean;
  includeCookies?: boolean;
  redact?: boolean;
  body?: string;
  context?: number;
}

export async function viewRequest(
  store: SnapshotStore,
  args: ViewRequestArgs,
): Promise<string> {
  const snap = await store.get();
  const e = snap.byId(args.id);
  if (!e) return notFoundError(snap.count, args.id);

  const includeHeaders = args.includeHeaders ?? false;
  const includeCookies = args.includeCookies ?? false;
  const redact = args.redact ?? true;
  const body = args.body ?? "full";
  const context = args.context ?? 1;

  let chosen: (readonly [string, string])[];
  let showHeaders: boolean;
  if (!includeHeaders && includeCookies) {
    chosen = e.request.headers.filter(([k]) => k.toLowerCase() === "cookie");
    showHeaders = chosen.length > 0;
  } else {
    chosen = filterHeaders(e.request.headers, includeCookies, "Cookie");
    showHeaders = includeHeaders;
  }
  chosen = applyRedaction(chosen, redact);

  const spec = resolveBodySpec(body, e.request.body.length);
  const sliced = sliceBody(e.request.body, spec, context);
  let note: string | undefined;
  if (sliced.truncated && (spec.startsWith("head:") || spec.startsWith("tail:"))) {
    note = `... (${sliced.totalLines} lines total; truncated)`;
  }
  return renderRequestView({
    e,
    headers: chosen,
    body: sliced.text,
    showHeaders,
    note,
  });
}

export interface ViewResponseArgs {
  id: number;
  includeHeaders?: boolean;
  includeSetCookie?: boolean;
  redact?: boolean;
  body?: string;
  context?: number;
}

export async function viewResponse(
  store: SnapshotStore,
  args: ViewResponseArgs,
): Promise<string> {
  const snap = await store.get();
  const e: HistoryEntry | undefined = snap.byId(args.id);
  if (!e) return notFoundError(snap.count, args.id);

  const includeHeaders = args.includeHeaders ?? false;
  const includeSetCookie = args.includeSetCookie ?? false;
  const redact = args.redact ?? true;
  const body = args.body ?? "auto";
  const context = args.context ?? 1;

  let chosen: (readonly [string, string])[];
  let showHeaders: boolean;
  if (!includeHeaders && includeSetCookie) {
    chosen = e.response.headers.filter(([k]) => k.toLowerCase() === "set-cookie");
    showHeaders = chosen.length > 0;
  } else {
    chosen = filterHeaders(e.response.headers, includeSetCookie, "Set-Cookie");
    showHeaders = includeHeaders;
  }
  chosen = applyRedaction(chosen, redact);

  const spec = resolveBodySpec(body, e.response.body.length);
  const sliced = sliceBody(e.response.body, spec, context);
  let note: string | undefined;
  if (body === "auto" && spec !== "full") {
    note =
      `... (auto-truncated; body is ${e.response.body.length} bytes — ` +
      `pass body="full" to override)`;
  } else if (sliced.truncated && (spec.startsWith("head:") || spec.startsWith("tail:"))) {
    note = `... (${sliced.totalLines} lines total; truncated)`;
  }
  return renderResponseView({
    e,
    headers: chosen,
    body: sliced.text,
    showHeaders,
    note,
  });
}
