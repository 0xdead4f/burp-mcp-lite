# Changelog

## 0.2.0 — Node.js / TypeScript rewrite

The runtime moved from Python to Node.js. Distribution is now via npm
(`npx -y burp-mcp-lite`) instead of `pip install`. No behavior changes are
intended in this release — the six tools, their input schemas, output formats,
default redaction, body DSL, regex windowing, timestamp source priority, and
SSE truncation salvage are all carried over.

### Why

- npm/`npx` is one command on every platform; `pip install`/venv friction
  was a real install obstacle for non-Python users.
- ~150–300 ms faster cold start per Claude Code session.
- `npm publish` is one command for distribution; the old "pip install from
  git URL" instruction aged poorly.

### Compatibility

- Tool names, descriptions, and input schemas unchanged.
- Output text format pinned by tests — same byte-for-byte where possible
  (line wrapping, footer, table column widths, `(no body)`, `<no matches>`,
  redaction stub `<redacted Nc>`).
- CLI flags carried over: `--url`, `--fixture`, `--ttl`, `--page-size`,
  `--max-entries`, `--verbose`.
- The previous Python source is preserved on the `pre-ts-rewrite` git branch.

### Known gaps (carried over)

- `_scheme_guess` always returns `https` — Burp's serializer drops scheme.
- `endpoints` is unbounded; pagination is a follow-up.

## 0.1.x — Python releases

See `pre-ts-rewrite` branch.
