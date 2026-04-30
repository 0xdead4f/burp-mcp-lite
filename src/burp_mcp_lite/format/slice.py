"""Body slicing per the `body:` arg DSL.

Accepted forms (case-insensitive on the prefix; argument is case-sensitive):
  full          -> whole body
  none          -> empty
  head:N        -> first N lines
  tail:N        -> last N lines
  /regex/       -> matching lines plus `context` lines on either side; deduped

`auto` is resolved by the caller before reaching here (it just becomes `full`
or `head:N`). This module is pure: input str -> output str.
"""

from __future__ import annotations

import re
from dataclasses import dataclass


@dataclass
class SliceResult:
    text: str
    truncated: bool  # whether we dropped content
    hit_count: int  # only meaningful for regex slicing
    total_lines: int


def _split_lines(body: str) -> list[str]:
    # Keep behavior consistent with parser: \r\n is normalized.
    if not body:
        return []
    return body.replace("\r\n", "\n").split("\n")


def slice_body(body: str, spec: str, context: int = 1) -> SliceResult:
    if not body:
        return SliceResult(text="", truncated=False, hit_count=0, total_lines=0)

    spec = spec.strip()
    lines = _split_lines(body)
    total = len(lines)

    if spec.lower() == "full":
        return SliceResult(text=body, truncated=False, hit_count=0, total_lines=total)

    if spec.lower() == "none":
        return SliceResult(text="", truncated=True, hit_count=0, total_lines=total)

    if spec.lower().startswith("head:"):
        try:
            n = max(0, int(spec.split(":", 1)[1]))
        except ValueError:
            return SliceResult(text=body, truncated=False, hit_count=0, total_lines=total)
        kept = lines[:n]
        return SliceResult(
            text="\n".join(kept),
            truncated=n < total,
            hit_count=0,
            total_lines=total,
        )

    if spec.lower().startswith("tail:"):
        try:
            n = max(0, int(spec.split(":", 1)[1]))
        except ValueError:
            return SliceResult(text=body, truncated=False, hit_count=0, total_lines=total)
        kept = lines[-n:] if n else []
        return SliceResult(
            text="\n".join(kept),
            truncated=n < total,
            hit_count=0,
            total_lines=total,
        )

    if spec.startswith("/") and spec.endswith("/") and len(spec) >= 2:
        pattern = spec[1:-1]
        try:
            rx = re.compile(pattern)
        except re.error:
            return SliceResult(
                text=f"<invalid regex: {pattern!r}>",
                truncated=True,
                hit_count=0,
                total_lines=total,
            )
        keep = set()
        hits = 0
        for i, line in enumerate(lines):
            if rx.search(line):
                hits += 1
                lo = max(0, i - context)
                hi = min(total, i + context + 1)
                for j in range(lo, hi):
                    keep.add(j)
        if not keep:
            return SliceResult(
                text="<no matches>", truncated=True, hit_count=0, total_lines=total
            )
        kept_indices = sorted(keep)
        # Render with line numbers for context; insert "..." between gaps.
        out_lines: list[str] = []
        prev: int | None = None
        for i in kept_indices:
            if prev is not None and i != prev + 1:
                out_lines.append("...")
            out_lines.append(f"[L{i + 1}] {lines[i]}")
            prev = i
        return SliceResult(
            text="\n".join(out_lines),
            truncated=len(keep) < total,
            hit_count=hits,
            total_lines=total,
        )

    # Unknown spec — fall back to full so we don't surprise the model.
    return SliceResult(text=body, truncated=False, hit_count=0, total_lines=total)
