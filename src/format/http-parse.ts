// Parse the raw HTTP message strings Burp emits via Montoya `toString()`.
// Burp returns request/response as HTTP/1.1-shaped wire-format text even for
// h2 traffic. We parse leniently: lines may use CRLF or LF; header values may
// contain colons (split on the first ":" only); body starts after the first
// blank line.

export type Header = readonly [name: string, value: string];

export interface ParsedRequest {
  readonly method: string;
  readonly target: string;
  readonly version: string;
  readonly headers: readonly Header[];
  readonly body: string;
}

export interface ParsedResponse {
  readonly version: string;
  readonly status: number;
  readonly reason: string;
  readonly headers: readonly Header[];
  readonly body: string;
}

function splitHeadBody(raw: string): [string, string] {
  // Accept either CRLFCRLF (proper) or LFLF (already normalized upstream).
  for (const sep of ["\r\n\r\n", "\n\n"] as const) {
    const idx = raw.indexOf(sep);
    if (idx !== -1) return [raw.slice(0, idx), raw.slice(idx + sep.length)];
  }
  return [raw, ""];
}

function splitLines(head: string): string[] {
  return head.replace(/\r\n/g, "\n").split("\n");
}

function parseHeaders(lines: readonly string[]): Header[] {
  const out: Header[] = [];
  for (const line of lines) {
    if (!line) continue;
    const idx = line.indexOf(":");
    if (idx === -1) {
      // Malformed; keep as a single-name header with empty value so we don't
      // silently drop it.
      out.push([line.trim(), ""]);
      continue;
    }
    const name = line.slice(0, idx).trim();
    const value = line.slice(idx + 1).replace(/^[ \t]+/, "");
    out.push([name, value]);
  }
  return out;
}

export function findHeader(headers: readonly Header[], name: string): string | undefined {
  const lname = name.toLowerCase();
  for (const [k, v] of headers) if (k.toLowerCase() === lname) return v;
  return undefined;
}

export function findAllHeaders(headers: readonly Header[], name: string): string[] {
  const lname = name.toLowerCase();
  const out: string[] = [];
  for (const [k, v] of headers) if (k.toLowerCase() === lname) out.push(v);
  return out;
}

export function requestHost(req: ParsedRequest): string {
  return findHeader(req.headers, "Host") ?? "";
}

export function requestPath(req: ParsedRequest): string {
  // request-target is usually origin-form ("/foo?bar=1"). For absolute-URI
  // (proxy form) we strip scheme://host. For "*" or authority-form return as-is.
  const t = req.target;
  if (t.startsWith("http://") || t.startsWith("https://")) {
    const noScheme = t.split("://", 2)[1] ?? "";
    const slash = noScheme.indexOf("/");
    return slash !== -1 ? noScheme.slice(slash) : "/";
  }
  return t;
}

export function responseContentType(resp: ParsedResponse): string {
  const ct = findHeader(resp.headers, "Content-Type") ?? "";
  return ct.split(";", 1)[0]!.trim().toLowerCase();
}

export function responseContentLength(resp: ParsedResponse): number {
  // Prefer the body length we actually have; fall back to the header.
  if (resp.body) return Buffer.byteLength(resp.body, "latin1");
  const cl = findHeader(resp.headers, "Content-Length");
  if (cl === undefined) return 0;
  const n = Number.parseInt(cl.trim(), 10);
  return Number.isFinite(n) && n >= 0 ? n : 0;
}

export function parseRequest(raw: string): ParsedRequest {
  const [head, body] = splitHeadBody(raw);
  const lines = splitLines(head);
  if (!lines.length || !lines[0]) {
    return { method: "", target: "", version: "", headers: [], body };
  }
  const parts = lines[0]!.split(" ", 3);
  return {
    method: parts[0] ?? "",
    target: parts[1] ?? "",
    version: parts[2] ?? "",
    headers: parseHeaders(lines.slice(1)),
    body,
  };
}

export function parseResponse(raw: string): ParsedResponse {
  const [head, body] = splitHeadBody(raw);
  const lines = splitLines(head);
  if (!lines.length || !lines[0]) {
    return { version: "", status: 0, reason: "", headers: [], body };
  }
  // First line: "HTTP/1.1 200 OK"; reason may contain spaces, so split into
  // at most 3 parts and concatenate the rest as the reason.
  const first = lines[0]!;
  const sp1 = first.indexOf(" ");
  const sp2 = sp1 === -1 ? -1 : first.indexOf(" ", sp1 + 1);
  const version = sp1 === -1 ? "" : first.slice(0, sp1);
  const statusStr = sp1 === -1 ? "" : first.slice(sp1 + 1, sp2 === -1 ? undefined : sp2);
  const reason = sp2 === -1 ? "" : first.slice(sp2 + 1);
  const status = Number.parseInt(statusStr, 10);
  return {
    version,
    status: Number.isFinite(status) ? status : 0,
    reason,
    headers: parseHeaders(lines.slice(1)),
    body,
  };
}

export function statusClass(status: number): string {
  if (status <= 0) return "0xx";
  return `${Math.floor(status / 100)}xx`;
}
