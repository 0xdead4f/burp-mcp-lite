# burp-mcp-lite

**Save 90%+ of your tokens on Burp Suite MCP — seven lean tools.**

This is a from-scratch Kotlin rewrite of PortSwigger's official Burp MCP extension, keeping the same SSE transport and Montoya entry point but cutting the tool surface down to **seven** tools tuned for low context cost. Headers off by default. Auth values redacted to length stubs. A `match` *predicate* tool that returns matched / not-matched + a bounded evidence snippet instead of dumping the whole body.

## What it replaces

The official `Burp MCP Server` ships 24 tools and serializes the full request + full response on every history entry. A 20-row history listing burns \~10k tokens before any work happens. `burp-mcp-lite` cuts that to a compact table by default, with field projection and filters on the way in and slicing on the way out.

Token cost, tool by tool — same job, both servers:

| Job | `burp-mcp-lite` | Burp's official MCP |
| --- | --- | --- |
| Browse 20 recent entries | `list_history` — \~400 tokens (compact table, headers off) | `get_proxy_http_history` — \~10 K tokens (full req + resp, JSON-serialized) |
| View one request | `view_request` — \~120 tokens (path + body, headers off, auth redacted) | one entry from `get_proxy_http_history` — \~1200 tokens (full bytes, headers + cookies on) |
| Verify a value is in a response | `match` — \~60 tokens (matched? + 240-char snippet centered on hit) | `get_proxy_http_history_regex` — full body up to the 5000-char cut |
| Inventory endpoints across history | `endpoints` — \~hundreds of tokens (deduplicated `method host path` + count) | no equivalent — page through `get_proxy_http_history` and dedup yourself (\~10 K+) |
| Stats (counts by method / status class / top hosts) | `stats` — \~50 tokens | no equivalent — page through `get_proxy_http_history` and aggregate yourself |

## Tools

| Tool | What it does |
| --- | --- |
| `list_history` | Browse + filter proxy history with field projection. Compact text table by default. |
| `view_request` | View one request by id. Headers + cookies OFF unless asked. Auth values redacted. |
| `view_response` | View one response by id. `body="auto"` truncates &gt;4 KB to `head:20`. |
| `match` | Predicate over one entry — matched? + small evidence snippet. Never the whole body. |
| `endpoints` | Deduplicated method+host+path inventory with hit counts (from proxy history). |
| `sitemap` | Browse Burp's site map (spider + scanner + proxy). `mode="domains"` (default) is just the host inventory; `mode="entries" domain=…` lists endpoints under one host — `dedup=true` (default) groups by method+path with last-seen status/mime + hit count, `dedup=false` flat-lists method/status/path. |
| `stats` | Aggregates: by method, status class, top hosts. |

All tools share these flags where they apply:

- `host=`, `path=` (regex), `method=` (string or array), `status=` (e.g. `4xx,500-503`), `mime=`
- `match=`, `match_in=` (`request.body|headers|all`, `response.body|headers|all`)
- `fields=` from `id,method,status,host,path,len,mime,time`
- `format=text|json`, `order=latest|oldest`, `refresh=true` (force snapshot rebuild)
- `body=full|none|head:N|tail:N|/regex/` (+ `context=N` lines)
- `redact=true` (default) on the view tools

## Build

```bash
git clone https://github.com/0xdead4f/burp-mcp-lite
cd burp-mcp-lite
./gradlew shadowJar    
```

Requires JDK 21. The build uses Gradle 9.x via the bundled wrapper.

## Install in Burp

1. Run `./gradlew shadowJar`.
2. In Burp: `Extensions → Installed → Add → Java`, pick `build/libs/burp-mcp-lite-0.3.1.jar`.
3. A new top-level **MCP Lite** tab appears. The server auto-starts on `127.0.0.1:9876`.

## Connect an MCP client

The server speaks **two transports on the same port**:

| Path | Transport | Use it from |
| --- | --- | --- |
| `/mcp` | Streamable HTTP (MCP 2024-11-05 / 2025-03-26 / 2025-06-18) | Claude Code (`--transport http`), any modern MCP HTTP client |
| `/sse` | SSE (legacy MCP HTTP transport) | Clients that only speak SSE |

### Claude Code — one-liner install

```bash
claude mcp add --transport http burp-mcp-lite http://127.0.0.1:9876/mcp
```

That's it. Restart your Claude Code session and `burp-mcp-lite` shows up in `/mcp`.

### Other clients

Any MCP client that can configure a remote connection works the same way — point it at `http://127.0.0.1:9876/mcp` (Streamable HTTP) or `http://127.0.0.1:9876/sse` (SSE) as your client requires. The MCP tab inside Burp shows live, copy-pasteable URLs with copy buttons.

## License

MIT — see `LICENSE`
