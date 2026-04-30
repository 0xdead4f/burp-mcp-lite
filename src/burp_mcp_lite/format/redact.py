"""Header value redaction.

Default-redact a small set of auth/secret-bearing header values to `<redacted>`.
Names are matched case-insensitively against the canonical list. We redact the
*value* only — name + length signal stays useful (the model can see "auth is
present" without burning tokens on the bytes).
"""

from __future__ import annotations

DEFAULT_REDACT_NAMES = frozenset(
    n.lower()
    for n in (
        "Authorization",
        "Proxy-Authorization",
        "Cookie",
        "Set-Cookie",
        "X-Api-Key",
        "X-Auth-Token",
        "X-Csrf-Token",
        "X-Access-Token",
    )
)


def redact_value(name: str, value: str) -> str:
    if name.lower() in DEFAULT_REDACT_NAMES:
        if not value:
            return "<redacted>"
        # Show length so the model can spot suspicious zero-length tokens.
        return f"<redacted {len(value)}c>"
    return value


def apply_redaction(
    headers: list[tuple[str, str]], redact: bool
) -> list[tuple[str, str]]:
    if not redact:
        return headers
    return [(k, redact_value(k, v)) for k, v in headers]
