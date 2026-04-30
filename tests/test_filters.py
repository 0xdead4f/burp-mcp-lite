from burp_mcp_lite.filters import build_filter, parse_status_filter
from burp_mcp_lite.snapshot import HistoryEntry


def make_entry(id_, method="GET", host="api.example.com", path="/x", status=200, ct="application/json", body=""):
    req = f"{method} {path} HTTP/1.1\r\nHost: {host}\r\n\r\n"
    resp = f"HTTP/1.1 {status} OK\r\nContent-Type: {ct}\r\n\r\n{body}"
    return HistoryEntry(id=id_, raw_request=req, raw_response=resp, notes=None)


def test_parse_status_exact():
    p = parse_status_filter("200")
    assert p(200) and not p(201)


def test_parse_status_class():
    p = parse_status_filter("4xx")
    assert p(400) and p(404) and p(499) and not p(500) and not p(399)


def test_parse_status_range():
    p = parse_status_filter("200-204")
    assert p(200) and p(204) and not p(205) and not p(199)


def test_parse_status_class_range():
    p = parse_status_filter("4xx-5xx")
    assert p(400) and p(500) and p(599) and not p(399) and not p(600)


def test_parse_status_mixed():
    p = parse_status_filter("200,4xx,500-503")
    assert p(200) and p(404) and p(502) and not p(201) and not p(504)


def test_filter_method_string():
    f = build_filter(method="POST")
    assert f.matches(make_entry(0, method="POST"))
    assert not f.matches(make_entry(0, method="GET"))


def test_filter_method_list():
    f = build_filter(method=["GET", "POST"])
    assert f.matches(make_entry(0, method="GET"))
    assert f.matches(make_entry(0, method="POST"))
    assert not f.matches(make_entry(0, method="PUT"))


def test_filter_host_substring():
    f = build_filter(host="api")
    assert f.matches(make_entry(0, host="api.example.com"))
    assert not f.matches(make_entry(0, host="cdn.example.com"))


def test_filter_path_regex():
    f = build_filter(path=r"^/v1/users$")
    assert f.matches(make_entry(0, path="/v1/users"))
    assert not f.matches(make_entry(0, path="/v1/users/42"))


def test_filter_status_class():
    f = build_filter(status="4xx-5xx")
    assert f.matches(make_entry(0, status=403))
    assert not f.matches(make_entry(0, status=200))


def test_filter_mime():
    f = build_filter(mime="json")
    assert f.matches(make_entry(0, ct="application/json"))
    assert not f.matches(make_entry(0, ct="text/html"))


def test_filter_match_response_body():
    f = build_filter(match="api_key")
    assert f.matches(make_entry(0, body='{"api_key":"x"}'))
    assert not f.matches(make_entry(0, body='{"hello":"world"}'))


def test_filter_combined():
    f = build_filter(method="POST", host="api", status="4xx", mime="json")
    yes = make_entry(0, method="POST", host="api.x", path="/p", status=401, ct="application/json")
    no_method = make_entry(0, method="GET", host="api.x", path="/p", status=401, ct="application/json")
    assert f.matches(yes)
    assert not f.matches(no_method)
