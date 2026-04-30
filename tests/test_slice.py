from burp_mcp_lite.format.slice import slice_body


BODY = "\n".join(f"line{i}" for i in range(1, 11))  # line1..line10


def test_full():
    r = slice_body(BODY, "full")
    assert r.text == BODY
    assert not r.truncated
    assert r.total_lines == 10


def test_none():
    r = slice_body(BODY, "none")
    assert r.text == ""
    assert r.truncated


def test_head():
    r = slice_body(BODY, "head:3")
    assert r.text == "line1\nline2\nline3"
    assert r.truncated


def test_head_overflow():
    r = slice_body(BODY, "head:100")
    assert r.text == BODY
    assert not r.truncated


def test_tail():
    r = slice_body(BODY, "tail:2")
    assert r.text == "line9\nline10"
    assert r.truncated


def test_regex_match_with_context():
    r = slice_body(BODY, "/line5/", context=1)
    assert r.hit_count == 1
    # Should include line4, line5, line6 with line numbers.
    assert "[L4] line4" in r.text
    assert "[L5] line5" in r.text
    assert "[L6] line6" in r.text


def test_regex_no_match():
    r = slice_body(BODY, "/zzzzz/")
    assert r.hit_count == 0
    assert r.text == "<no matches>"


def test_regex_invalid():
    r = slice_body(BODY, "/[/")
    assert "invalid regex" in r.text


def test_regex_gap_separator():
    body = "\n".join(["a", "b", "FIND_ME", "c", "d", "e", "f", "FIND_ME", "g"])
    r = slice_body(body, "/FIND_ME/", context=0)
    # Two non-adjacent hits should be separated by "..."
    assert r.text.count("...") == 1
    assert r.hit_count == 2


def test_empty_body():
    r = slice_body("", "full")
    assert r.text == ""
    assert r.total_lines == 0
