// match — predicate query over a single entry. Returns matched + small evidence.

import { resolveMatchTarget } from "../filters.js";
import { errorLine, renderMatch } from "../format/render.js";
import { windowedLine } from "../format/slice.js";
import type { SnapshotStore } from "../snapshot.js";

export interface MatchArgs {
  id: number;
  pattern: string;
  target?: string;
  caseSensitive?: boolean;
  context?: number;
  maxHits?: number;
}

export async function match(
  store: SnapshotStore,
  args: MatchArgs,
): Promise<string> {
  const target = args.target ?? "response.body";
  const caseSensitive = args.caseSensitive ?? false;
  const context = args.context ?? 0;
  const maxHits = args.maxHits ?? 10;

  const snap = await store.get();
  const e = snap.byId(args.id);
  if (!e) {
    return errorLine(
      `id ${args.id} not found in current history snapshot (have ids 0–${snap.count ? snap.count - 1 : -1})`,
    );
  }
  let rx: RegExp;
  try {
    rx = new RegExp(args.pattern, caseSensitive ? "" : "i");
  } catch (exc) {
    return errorLine(`invalid regex ${JSON.stringify(args.pattern)}: ${(exc as Error).message}`);
  }

  const text = resolveMatchTarget(e, target);
  const lines = text ? text.replace(/\r\n/g, "\n").split("\n") : [];

  const hitIndices: number[] = [];
  for (let i = 0; i < lines.length; i++) {
    if (rx.test(lines[i]!)) hitIndices.push(i);
  }
  if (hitIndices.length === 0) {
    return renderMatch({ matched: false, target, hits: 0, snippets: [] });
  }

  const hitSet = new Set(hitIndices);
  const snippets: string[] = [];
  const rendered = new Set<number>();
  for (const i of hitIndices.slice(0, maxHits)) {
    const lo = Math.max(0, i - context);
    const hi = Math.min(lines.length, i + context + 1);
    if (snippets.length > 0) {
      const maxRendered = Math.max(...rendered);
      if (lo > maxRendered + 1) snippets.push("...");
    }
    for (let j = lo; j < hi; j++) {
      if (rendered.has(j)) continue;
      rendered.add(j);
      const windowRx = hitSet.has(j) ? rx : null;
      snippets.push(`[L${j + 1}] ${windowedLine(lines[j]!, windowRx)}`);
    }
  }
  return renderMatch({
    matched: true,
    target,
    hits: hitIndices.length,
    snippets,
  });
}
