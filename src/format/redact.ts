// Header value redaction. Default-redact a small set of auth/secret-bearing
// header values to `<redacted Nc>` (length stub). The model can still see
// "auth is present" without burning tokens on the bytes.

import type { Header } from "./http-parse.js";

export const DEFAULT_REDACT_NAMES: ReadonlySet<string> = new Set(
  [
    "Authorization",
    "Proxy-Authorization",
    "Cookie",
    "Set-Cookie",
    "X-Api-Key",
    "X-Auth-Token",
    "X-Csrf-Token",
    "X-Access-Token",
  ].map((n) => n.toLowerCase()),
);

export function redactValue(name: string, value: string): string {
  if (!DEFAULT_REDACT_NAMES.has(name.toLowerCase())) return value;
  if (!value) return "<redacted>";
  return `<redacted ${value.length}c>`;
}

export function applyRedaction(
  headers: readonly Header[],
  redact: boolean,
): Header[] {
  if (!redact) return headers.slice();
  return headers.map(([k, v]) => [k, redactValue(k, v)] as const);
}
