// list_history — browse + filter proxy history with field projection.

import { applyFilter, buildFilter } from "../filters.js";
import {
  ALL_FIELDS,
  DEFAULT_FIELDS,
  type Field,
  renderHistoryNdjson,
  renderHistoryTable,
} from "../format/render.js";
import type { SnapshotStore } from "../snapshot.js";

export interface ListHistoryArgs {
  limit?: number;
  offset?: number;
  fields?: readonly string[];
  host?: string;
  path?: string;
  method?: string | readonly string[];
  status?: string;
  mime?: string;
  match?: string;
  matchIn?: string;
  order?: "latest" | "oldest";
  format?: "text" | "json";
  refresh?: boolean;
}

function isField(f: string): f is Field {
  return ALL_FIELDS.has(f as Field);
}

export async function listHistory(
  store: SnapshotStore,
  args: ListHistoryArgs = {},
): Promise<string> {
  const limit = args.limit ?? 20;
  const offset = args.offset ?? 0;
  const order = args.order ?? "latest";
  const format = args.format ?? "text";

  const snap = await store.get(args.refresh ?? false);
  const f = buildFilter({
    host: args.host,
    path: args.path,
    method: args.method,
    status: args.status,
    mime: args.mime,
    match: args.match,
    matchIn: args.matchIn,
  });

  let filtered = applyFilter(snap.entries, f);
  if (order === "latest") filtered = [...filtered].reverse();

  const total = filtered.length;
  const page = filtered.slice(offset, offset + Math.max(0, limit));

  let active: Field[];
  if (args.fields === undefined) {
    active = [...DEFAULT_FIELDS];
  } else {
    active = args.fields.filter(isField);
    if (active.length === 0) active = [...DEFAULT_FIELDS];
  }

  if (format === "json") return renderHistoryNdjson(page, active);
  return renderHistoryTable(page, active, total, offset);
}
