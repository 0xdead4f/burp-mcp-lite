# burp-mcp-lite

Token-efficient middleware MCP server for Burp Suite proxy history.

Sits between an LLM client and Burp's official MCP server, exposing six tools
tuned for low context cost: field-projected listings, default-off headers and
cookies, default-redacted auth values, and predicate (yes/no + small evidence)
queries instead of full payload dumps.

## Why

The official Burp MCP server exposes ~20 tools and serializes the full request
plus full response on every history entry. A 20-row history listing burns
~10k tokens before any work happens. `burp-mcp-lite` cuts the schema to ~1k
tokens and lets the model ask for *exactly* the field/slice it needs.

## Install

Requires Python 3.10+ and Burp Suite with the official MCP extension enabled
(defaults to `http://127.0.0.1:9876/`).

### One-liner (Claude Code)

```bash
pip install git+https://github.com/0xdead4f/burp-mcp-lite.git \
  && claude mcp add burp -- burp-mcp-lite
```

That's it. Restart Claude Code, run `/mcp`, you should see `burp` listed with
six tools.

### Alternatives

**`uvx` (no global install):**

```bash
claude mcp add burp -- uvx --from git+https://github.com/0xdead4f/burp-mcp-lite.git burp-mcp-lite
```

**Manual config** (Claude Desktop or any MCP client) — drop this into
`~/Library/Application Support/Claude/claude_desktop_config.json` (macOS) or
the equivalent on your platform:

```json
{
  "mcpServers": {
    "burp": {
      "command": "burp-mcp-lite"
    }
  }
}
```

**Local checkout (for development):**

```bash
git clone https://github.com/0xdead4f/burp-mcp-lite.git
cd burp-mcp-lite
pip install -e .
claude mcp add burp -- burp-mcp-lite
```

### Common flags

Pass extra flags after the `--`:

```bash
claude mcp add burp -- burp-mcp-lite --max-entries 500 --ttl 60
```

| Flag | Purpose |
|---|---|
| `--url URL` | Override Burp MCP URL (default `http://127.0.0.1:9876/`) |
| `--page-size N` | Per-call page size from Burp (default 20 — keep it low; large pages can close the SSE pipe mid-fetch) |
| `--max-entries N` | Cap on history entries per refresh (default 5000) |
| `--ttl SEC` | Snapshot cache TTL before auto-refresh (default 30) |
| `--fixture FILE` | Read history from a JSON file instead of Burp (offline dev) |
| `-v, --verbose` | Log to stderr |

### Verify

```bash
# Live: must have Burp running
burp-mcp-lite --verbose

# Offline smoke test — no Burp needed
burp-mcp-lite --fixture tests/fixtures/sample.json
```

## Tools

| Tool | Purpose |
|---|---|
| `list_history` | Browse + filter proxy history with field projection. |
| `view_request` | View one request by id. Headers/cookies off by default. |
| `view_response` | View one response by id. Auto-truncates >4 KB bodies. |
| `match` | Predicate query: does the entry match a regex? Yes/no + evidence. |
| `endpoints` | Deduplicated method+host+path inventory with hit counts. |
| `stats` | Aggregates: by method, status class, top hosts. |

### Body-slice DSL (used by `view_request` and `view_response`)

- `full` — whole body
- `none` — empty
- `head:N` — first N lines
- `tail:N` — last N lines
- `/regex/` — matching lines plus `context` lines on either side
- `auto` *(view_response default)* — full if <4 KB, else head:20

### Status filter syntax (`list_history` `status` arg)

- `200` — exact
- `4xx` — class
- `200-204` — range
- `4xx-5xx` — class range
- `200,4xx,500` — comma-separated mix

## Tests

```bash
PYTHONPATH=src python3.11 -m pytest tests/ -q
```

Unit tests for parser/redact/slice/filter; tool tests against an in-memory
snapshot; an end-to-end test that spawns the real stdio server as a subprocess
and exercises every tool via the MCP client.

## Repo layout

```
src/burp_mcp_lite/
├── __main__.py        — CLI entry point
├── server.py          — MCP stdio server + tool registration
├── upstream.py        — SSE client to Burp + FixtureUpstream
├── snapshot.py        — in-memory cache + stable id mapping
├── filters.py         — status range parser, host/path/method/mime/regex predicates
├── tools/             — one file per tool
└── format/            — http_parse, redact, slice, render
```
