// Shared fixtures: in-memory SnapshotStore with sample data. Mirror of the
// previous Python conftest.py SAMPLE (constructed so each tool has something
// interesting to find).

import { SnapshotStore, type RawEntry } from "../src/snapshot.js";

function req(args: {
  method: string;
  host: string;
  path: string;
  headers?: ReadonlyArray<readonly [string, string]>;
  body?: string;
}): string {
  const head = [`${args.method} ${args.path} HTTP/1.1`, `Host: ${args.host}`];
  for (const [k, v] of args.headers ?? []) head.push(`${k}: ${v}`);
  return head.join("\r\n") + "\r\n\r\n" + (args.body ?? "");
}

function resp(args: {
  status: number;
  ct?: string;
  headers?: ReadonlyArray<readonly [string, string]>;
  body?: string;
}): string {
  const head = [`HTTP/1.1 ${args.status} OK`, `Content-Type: ${args.ct ?? "application/json"}`];
  for (const [k, v] of args.headers ?? []) head.push(`${k}: ${v}`);
  return head.join("\r\n") + "\r\n\r\n" + (args.body ?? "");
}

export const SAMPLE: RawEntry[] = [
  // 0 — auth GET
  {
    request: req({
      method: "GET",
      host: "api.example.com",
      path: "/v1/users?id=42",
      headers: [
        ["Authorization", "Bearer eyJabc.def.ghi"],
        ["Accept", "application/json"],
      ],
    }),
    response: resp({
      status: 200,
      body: '{"id":42,"name":"alice","email":"alice@example.com"}',
    }),
    notes: null,
  },
  // 1 — failed login
  {
    request: req({
      method: "POST",
      host: "api.example.com",
      path: "/v1/login",
      headers: [["Content-Type", "application/json"]],
      body: '{"user":"alice","pw":"hunter2"}',
    }),
    response: resp({ status: 401, body: '{"error":"invalid_credentials"}' }),
    notes: null,
  },
  // 2 — admin redirect
  {
    request: req({ method: "GET", host: "api.example.com", path: "/admin" }),
    response: resp({
      status: 302,
      headers: [
        ["Location", "/login"],
        ["Set-Cookie", "session=abc; HttpOnly"],
      ],
      body: "",
    }),
    notes: null,
  },
  // 3 — large static file (just over the auto-trunc threshold)
  {
    request: req({ method: "GET", host: "cdn.example.com", path: "/static/app.js" }),
    response: resp({
      status: 200,
      ct: "application/javascript",
      body: "// line\n".repeat(600),
    }),
    notes: null,
  },
  // 4 — body containing a token to find
  {
    request: req({
      method: "GET",
      host: "api.example.com",
      path: "/v1/profile",
      headers: [["Authorization", "Bearer t.t.t"]],
    }),
    response: resp({
      status: 200,
      body: '{"token":"eyJhbGciOiJIUzI1NiJ9.payload.sig","ok":true}',
    }),
    notes: null,
  },
  // 5 — POST 500
  {
    request: req({
      method: "POST",
      host: "api.example.com",
      path: "/v1/checkout",
      body: '{"item":"x","qty":1}',
    }),
    response: resp({ status: 500, body: '{"error":"internal"}' }),
    notes: null,
  },
];

export function buildSampleStore(): SnapshotStore {
  const fetch = async (offset = 0) => SAMPLE.slice(offset);
  return new SnapshotStore(fetch, 999);
}
