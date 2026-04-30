"""Parse the raw HTTP message strings that Burp emits via Montoya `toString()`.

Burp returns request/response as HTTP/1.1-shaped wire-format text even for h2
traffic. We parse leniently:
  - Lines may use \r\n or \n (we accept both, normalize internally).
  - Header values may contain colons; we split on the first ":" only.
  - The body starts after the first blank line. Everything after is body bytes
    decoded as latin-1 (we keep bytes round-trippable; UTF-8 view is opt-in).

We deliberately do not validate or canonicalize. The model gets what Burp saw.
"""

from __future__ import annotations

from dataclasses import dataclass, field
from typing import Optional


def _split_head_body(raw: str) -> tuple[str, str]:
    # Accept either CRLFCRLF (proper) or LFLF (already normalized upstream).
    for sep in ("\r\n\r\n", "\n\n"):
        idx = raw.find(sep)
        if idx != -1:
            return raw[:idx], raw[idx + len(sep) :]
    return raw, ""


def _split_lines(head: str) -> list[str]:
    # Normalize CRLF -> LF for splitting; preserve original trailing whitespace
    # in values by stripping only line terminators.
    return head.replace("\r\n", "\n").split("\n")


def _parse_headers(lines: list[str]) -> list[tuple[str, str]]:
    out: list[tuple[str, str]] = []
    for line in lines:
        if not line:
            continue
        idx = line.find(":")
        if idx == -1:
            # Malformed; keep as a single-name header with empty value so we
            # don't silently drop it.
            out.append((line.strip(), ""))
            continue
        name = line[:idx].strip()
        value = line[idx + 1 :].lstrip(" \t")
        out.append((name, value))
    return out


@dataclass
class ParsedRequest:
    method: str
    target: str  # the request-target as sent (path?query, *, absolute-URI, authority)
    version: str  # e.g. "HTTP/1.1"
    headers: list[tuple[str, str]] = field(default_factory=list)
    body: str = ""

    @property
    def host(self) -> str:
        return self.header("Host") or ""

    @property
    def path(self) -> str:
        # request-target is usually origin-form: "/foo?bar=1". For absolute-URI
        # (proxy form) we strip scheme://host. For "*" or authority-form we
        # return as-is.
        t = self.target
        if t.startswith(("http://", "https://")):
            try:
                # cut after the host: scheme://host[:port]/path...
                no_scheme = t.split("://", 1)[1]
                slash = no_scheme.find("/")
                return no_scheme[slash:] if slash != -1 else "/"
            except Exception:
                return t
        return t

    def header(self, name: str) -> Optional[str]:
        lname = name.lower()
        for k, v in self.headers:
            if k.lower() == lname:
                return v
        return None

    def headers_all(self, name: str) -> list[str]:
        lname = name.lower()
        return [v for k, v in self.headers if k.lower() == lname]


@dataclass
class ParsedResponse:
    version: str  # e.g. "HTTP/1.1"
    status: int
    reason: str
    headers: list[tuple[str, str]] = field(default_factory=list)
    body: str = ""

    @property
    def content_type(self) -> str:
        ct = self.header("Content-Type") or ""
        # strip parameters (charset etc.)
        return ct.split(";", 1)[0].strip().lower()

    @property
    def content_length(self) -> int:
        # Prefer body length we actually have; fall back to header.
        if self.body:
            return len(self.body.encode("latin-1", errors="replace"))
        cl = self.header("Content-Length")
        if cl is None:
            return 0
        try:
            return int(cl.strip())
        except ValueError:
            return 0

    def header(self, name: str) -> Optional[str]:
        lname = name.lower()
        for k, v in self.headers:
            if k.lower() == lname:
                return v
        return None

    def headers_all(self, name: str) -> list[str]:
        lname = name.lower()
        return [v for k, v in self.headers if k.lower() == lname]


def parse_request(raw: str) -> ParsedRequest:
    head, body = _split_head_body(raw)
    lines = _split_lines(head)
    if not lines or not lines[0]:
        return ParsedRequest(method="", target="", version="", body=body)
    parts = lines[0].split(" ", 2)
    method = parts[0] if len(parts) > 0 else ""
    target = parts[1] if len(parts) > 1 else ""
    version = parts[2] if len(parts) > 2 else ""
    headers = _parse_headers(lines[1:])
    return ParsedRequest(method=method, target=target, version=version, headers=headers, body=body)


def parse_response(raw: str) -> ParsedResponse:
    head, body = _split_head_body(raw)
    lines = _split_lines(head)
    if not lines or not lines[0]:
        return ParsedResponse(version="", status=0, reason="", body=body)
    parts = lines[0].split(" ", 2)
    version = parts[0] if len(parts) > 0 else ""
    try:
        status = int(parts[1]) if len(parts) > 1 else 0
    except ValueError:
        status = 0
    reason = parts[2] if len(parts) > 2 else ""
    headers = _parse_headers(lines[1:])
    return ParsedResponse(
        version=version, status=status, reason=reason, headers=headers, body=body
    )


def status_class(status: int) -> str:
    if status <= 0:
        return "0xx"
    return f"{status // 100}xx"
