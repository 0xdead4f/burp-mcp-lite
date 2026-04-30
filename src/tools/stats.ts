// stats — small aggregate over the cached snapshot.

import { statusClass } from "../format/http-parse.js";
import { renderStats } from "../format/render.js";
import type { SnapshotStore } from "../snapshot.js";

export async function stats(store: SnapshotStore): Promise<string> {
  const snap = await store.get();
  const byMethod: Record<string, number> = {};
  const byClass: Record<string, number> = {};
  const byHost: Record<string, number> = {};

  for (const e of snap.entries) {
    const m = e.request.method || "-";
    byMethod[m] = (byMethod[m] ?? 0) + 1;
    const cls = statusClass(e.response.status);
    byClass[cls] = (byClass[cls] ?? 0) + 1;
    if (e.request.host) {
      byHost[e.request.host] = (byHost[e.request.host] ?? 0) + 1;
    }
  }

  const topHosts = Object.entries(byHost)
    .sort((a, b) => b[1] - a[1])
    .slice(0, 5)
    .map(([h, n]) => [h, n] as const);

  return renderStats({
    total: snap.count,
    byMethod,
    byClass,
    byHost: topHosts,
  });
}
