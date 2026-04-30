// endpoints — deduplicated method+host+path inventory across the snapshot.

import { applyFilter, buildFilter } from "../filters.js";
import { renderEndpoints } from "../format/render.js";
import type { SnapshotStore } from "../snapshot.js";

export interface EndpointsArgs {
  host?: string;
  path?: string;
  method?: string | readonly string[];
}

export async function endpoints(
  store: SnapshotStore,
  args: EndpointsArgs = {},
): Promise<string> {
  const snap = await store.get();
  const f = buildFilter({ host: args.host, path: args.path, method: args.method });
  const entries = applyFilter(snap.entries, f);

  const counts = new Map<string, number>();
  // Maintain a parallel map for the tuple to avoid splitting strings on render.
  const tuples = new Map<string, [string, string, string]>();

  for (const e of entries) {
    const pathNoQuery = (e.request.path || "").split("?", 1)[0]!;
    const key = `${e.request.method}|${e.request.host}|${pathNoQuery}`;
    counts.set(key, (counts.get(key) ?? 0) + 1);
    if (!tuples.has(key)) tuples.set(key, [e.request.method, e.request.host, pathNoQuery]);
  }

  const rows: [string, string, string, number][] = [];
  for (const [key, count] of counts) {
    const t = tuples.get(key)!;
    rows.push([t[0], t[1], t[2], count]);
  }
  rows.sort((a, b) => {
    if (b[3] !== a[3]) return b[3] - a[3];
    if (a[1] !== b[1]) return a[1] < b[1] ? -1 : 1;
    return a[2] < b[2] ? -1 : a[2] > b[2] ? 1 : 0;
  });
  return renderEndpoints(rows);
}
