// Body slicing per the `body:` arg DSL.
//
// Accepted forms (case-insensitive on the prefix; argument is case-sensitive):
//   full          -> whole body
//   none          -> empty
//   head:N        -> first N lines
//   tail:N        -> last N lines
//   /regex/       -> matching lines plus `context` lines on either side
//
// `auto` is resolved by the caller before reaching here.

// Per-line cap for emitted regex snippets. A "line" in the wild can be a 50KB
// minified-JSON blob — emitting it whole defeats the predicate viewer. We
// center the window on the match when there is one, head-trim otherwise.
export const SNIPPET_LINE_CAP = 240;
export const ELLIPSIS = "…";

export interface SliceResult {
  text: string;
  truncated: boolean;
  hitCount: number;
  totalLines: number;
}

function splitBodyLines(body: string): string[] {
  if (!body) return [];
  return body.replace(/\r\n/g, "\n").split("\n");
}

export function windowedLine(
  line: string,
  rx: RegExp | null,
  cap: number = SNIPPET_LINE_CAP,
): string {
  if (line.length <= cap) return line;
  // Use a fresh exec/lastIndex isn't an issue if `rx` is non-global; callers
  // pass non-global patterns. We only need first match.
  const m = rx ? rx.exec(line) : null;
  // Reset lastIndex so callers can reuse the same RegExp.
  if (rx && rx.global) rx.lastIndex = 0;
  if (!m) {
    return line.slice(0, cap - 1) + ELLIPSIS;
  }
  const start = m.index;
  const end = m.index + m[0].length;
  const matchLen = end - start;
  if (matchLen + 2 >= cap) {
    return ELLIPSIS + line.slice(start, start + cap - 2) + ELLIPSIS;
  }
  const remaining = cap - matchLen - 2;
  const pre = Math.floor(remaining / 2);
  const post = remaining - pre;
  const winStart = Math.max(0, start - pre);
  const winEnd = Math.min(line.length, end + post);
  let out = line.slice(winStart, winEnd);
  if (winStart > 0) out = ELLIPSIS + out;
  if (winEnd < line.length) out = out + ELLIPSIS;
  return out;
}

export function sliceBody(
  body: string,
  spec: string,
  context: number = 1,
): SliceResult {
  if (!body) {
    return { text: "", truncated: false, hitCount: 0, totalLines: 0 };
  }

  const trimmedSpec = spec.trim();
  const lines = splitBodyLines(body);
  const total = lines.length;
  const lower = trimmedSpec.toLowerCase();

  if (lower === "full") {
    return { text: body, truncated: false, hitCount: 0, totalLines: total };
  }
  if (lower === "none") {
    return { text: "", truncated: true, hitCount: 0, totalLines: total };
  }

  if (lower.startsWith("head:")) {
    const n = Math.max(0, Number.parseInt(trimmedSpec.slice(5), 10) || 0);
    const kept = lines.slice(0, n);
    return {
      text: kept.join("\n"),
      truncated: n < total,
      hitCount: 0,
      totalLines: total,
    };
  }

  if (lower.startsWith("tail:")) {
    const n = Math.max(0, Number.parseInt(trimmedSpec.slice(5), 10) || 0);
    const kept = n ? lines.slice(-n) : [];
    return {
      text: kept.join("\n"),
      truncated: n < total,
      hitCount: 0,
      totalLines: total,
    };
  }

  if (
    trimmedSpec.startsWith("/") &&
    trimmedSpec.endsWith("/") &&
    trimmedSpec.length >= 2
  ) {
    const pattern = trimmedSpec.slice(1, -1);
    let rx: RegExp;
    try {
      rx = new RegExp(pattern);
    } catch {
      return {
        text: `<invalid regex: ${JSON.stringify(pattern)}>`,
        truncated: true,
        hitCount: 0,
        totalLines: total,
      };
    }

    const keep = new Set<number>();
    const hitLines = new Set<number>();
    let hits = 0;
    for (let i = 0; i < lines.length; i++) {
      if (rx.test(lines[i]!)) {
        hits += 1;
        hitLines.add(i);
        const lo = Math.max(0, i - context);
        const hi = Math.min(total, i + context + 1);
        for (let j = lo; j < hi; j++) keep.add(j);
      }
    }
    if (keep.size === 0) {
      return {
        text: "<no matches>",
        truncated: true,
        hitCount: 0,
        totalLines: total,
      };
    }
    const keptIndices = [...keep].sort((a, b) => a - b);
    const out: string[] = [];
    let prev: number | null = null;
    let anyLineTruncated = false;
    for (const i of keptIndices) {
      if (prev !== null && i !== prev + 1) out.push("...");
      const windowRx = hitLines.has(i) ? rx : null;
      const rendered = windowedLine(lines[i]!, windowRx);
      if (rendered !== lines[i]) anyLineTruncated = true;
      out.push(`[L${i + 1}] ${rendered}`);
      prev = i;
    }
    return {
      text: out.join("\n"),
      truncated: anyLineTruncated || keep.size < total,
      hitCount: hits,
      totalLines: total,
    };
  }

  // Unknown spec — fall back to full so we don't surprise the model.
  return { text: body, truncated: false, hitCount: 0, totalLines: total };
}
