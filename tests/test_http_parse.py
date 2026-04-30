from burp_mcp_lite.format.http_parse import (
    parse_request,
    parse_response,
    status_class,
)


REQ = (
    "GET /v1/users?id=42 HTTP/1.1\r\n"
    "Host: api.example.com\r\n"
    "Accept: application/json\r\n"
    "Authorization: Bearer xyz.abc.123\r\n"
    "\r\n"
)

POST_REQ = (
    "POST /v1/login HTTP/1.1\r\n"
    "Host: api.example.com\r\n"
    "Content-Type: application/json\r\n"
    "Content-Length: 31\r\n"
    "\r\n"
    '{"user":"alice","pw":"hunter2"}'
)

RESP = (
    "HTTP/1.1 200 OK\r\n"
    "Content-Type: application/json; charset=utf-8\r\n"
    "Content-Length: 53\r\n"
    "Set-Cookie: session=abc; HttpOnly\r\n"
    "\r\n"
    '{"id":42,"name":"alice","email":"alice@example.com"}'
)


def test_request_basics():
    r = parse_request(REQ)
    assert r.method == "GET"
    assert r.target == "/v1/users?id=42"
    assert r.path == "/v1/users?id=42"
    assert r.host == "api.example.com"
    assert r.version == "HTTP/1.1"
    assert r.header("Authorization") == "Bearer xyz.abc.123"
    assert r.header("authorization") == "Bearer xyz.abc.123"  # case-insensitive
    assert r.header("missing") is None
    assert r.body == ""


def test_request_post_with_body():
    r = parse_request(POST_REQ)
    assert r.method == "POST"
    assert r.body == '{"user":"alice","pw":"hunter2"}'


def test_request_absolute_uri_target():
    raw = "GET https://api.example.com/foo?bar=1 HTTP/1.1\r\nHost: api.example.com\r\n\r\n"
    r = parse_request(raw)
    assert r.path == "/foo?bar=1"


def test_request_absolute_uri_no_path():
    raw = "GET https://api.example.com HTTP/1.1\r\nHost: api.example.com\r\n\r\n"
    r = parse_request(raw)
    assert r.path == "/"


def test_response_basics():
    r = parse_response(RESP)
    assert r.status == 200
    assert r.reason == "OK"
    assert r.content_type == "application/json"
    assert r.header("Set-Cookie") == "session=abc; HttpOnly"
    assert "alice@example.com" in r.body


def test_response_content_length_from_body():
    r = parse_response(RESP)
    assert r.content_length == len(r.body)


def test_response_multiple_set_cookies():
    raw = (
        "HTTP/1.1 200 OK\r\n"
        "Set-Cookie: a=1\r\n"
        "Set-Cookie: b=2\r\n"
        "\r\n"
    )
    r = parse_response(raw)
    assert r.headers_all("Set-Cookie") == ["a=1", "b=2"]


def test_lf_only_separator():
    raw = "GET /x HTTP/1.1\nHost: a\n\nbody"
    r = parse_request(raw)
    assert r.method == "GET"
    assert r.host == "a"
    assert r.body == "body"


def test_status_class():
    assert status_class(200) == "2xx"
    assert status_class(404) == "4xx"
    assert status_class(0) == "0xx"
