"""Filter helpers: status range parsing, host/path/method/mime predicates."""

from __future__ import annotations

import re
from dataclasses import dataclass
from typing import Callable, Iterable, Optional, Sequence, Union

from .snapshot import HistoryEntry


def parse_status_filter(spec: str) -> Callable[[int], bool]:
    """Parse a status filter spec.

    Accepted forms (comma-separated, AND-of-OR within a single arg):
      "200"            -> exact
      "2xx"            -> class
      "200,201,204"    -> set
      "400-499"        -> range (inclusive)
      "4xx-5xx"        -> two classes (200..599 covered)
      "200,4xx"        -> mix

    Empty / None handled by callers; this expects a non-empty string.
    """
    spec = spec.strip()
    matchers: list[Callable[[int], bool]] = []
    for token in spec.split(","):
        token = token.strip().lower()
        if not token:
            continue
        if "-" in token:
            lo_s, hi_s = token.split("-", 1)
            lo = _expand_low(lo_s)
            hi = _expand_high(hi_s)
            matchers.append(lambda s, lo=lo, hi=hi: lo <= s <= hi)
        elif token.endswith("xx") and len(token) == 3 and token[0].isdigit():
            cls = int(token[0])
            matchers.append(
                lambda s, cls=cls: (cls * 100) <= s <= (cls * 100 + 99)
            )
        else:
            try:
                exact = int(token)
            except ValueError:
                # unknown token: treat as never-match so the filter doesn't
                # silently let everything through.
                matchers.append(lambda s: False)
                continue
            matchers.append(lambda s, exact=exact: s == exact)
    if not matchers:
        return lambda s: True
    return lambda s: any(m(s) for m in matchers)


def _expand_low(tok: str) -> int:
    tok = tok.strip().lower()
    if tok.endswith("xx") and len(tok) == 3:
        return int(tok[0]) * 100
    return int(tok)


def _expand_high(tok: str) -> int:
    tok = tok.strip().lower()
    if tok.endswith("xx") and len(tok) == 3:
        return int(tok[0]) * 100 + 99
    return int(tok)


def _normalize_methods(m: Union[None, str, Sequence[str]]) -> Optional[set[str]]:
    if m is None:
        return None
    if isinstance(m, str):
        items = [x.strip() for x in m.split(",") if x.strip()]
    else:
        items = [x.strip() for x in m if x and x.strip()]
    if not items:
        return None
    return {x.upper() for x in items}


@dataclass
class HistoryFilter:
    host_substring: Optional[str] = None
    path_regex: Optional[re.Pattern] = None
    methods: Optional[set[str]] = None
    status_pred: Optional[Callable[[int], bool]] = None
    mime_substring: Optional[str] = None
    body_match_regex: Optional[re.Pattern] = None
    match_in: str = "response.body"

    def matches(self, e: HistoryEntry) -> bool:
        if self.methods and e.request.method.upper() not in self.methods:
            return False
        if self.host_substring and self.host_substring.lower() not in e.request.host.lower():
            return False
        if self.path_regex and not self.path_regex.search(e.request.path):
            return False
        if self.status_pred and not self.status_pred(e.response.status):
            return False
        if self.mime_substring:
            if self.mime_substring.lower() not in (e.response.content_type or "").lower():
                return False
        if self.body_match_regex:
            target = _resolve_match_target(e, self.match_in)
            if not self.body_match_regex.search(target):
                return False
        return True


def _resolve_match_target(e: HistoryEntry, target: str) -> str:
    target = target.strip().lower()
    if target == "response.body":
        return e.response.body
    if target == "response.headers":
        return _render_headers(e.response.headers)
    if target == "response.all":
        return _render_headers(e.response.headers) + "\n\n" + e.response.body
    if target == "request.body":
        return e.request.body
    if target == "request.headers":
        return _render_headers(e.request.headers)
    if target == "request.all":
        return _render_headers(e.request.headers) + "\n\n" + e.request.body
    return e.response.body


def _render_headers(headers: list[tuple[str, str]]) -> str:
    return "\n".join(f"{k}: {v}" for k, v in headers)


def build_filter(
    *,
    host: Optional[str] = None,
    path: Optional[str] = None,
    method: Union[None, str, Sequence[str]] = None,
    status: Optional[str] = None,
    mime: Optional[str] = None,
    match: Optional[str] = None,
    match_in: str = "response.body",
    case_sensitive: bool = False,
) -> HistoryFilter:
    flags = 0 if case_sensitive else re.IGNORECASE
    return HistoryFilter(
        host_substring=host,
        path_regex=re.compile(path, flags) if path else None,
        methods=_normalize_methods(method),
        status_pred=parse_status_filter(status) if status else None,
        mime_substring=mime,
        body_match_regex=re.compile(match, flags) if match else None,
        match_in=match_in,
    )


def apply_filter(entries: Iterable[HistoryEntry], f: HistoryFilter) -> list[HistoryEntry]:
    return [e for e in entries if f.matches(e)]
