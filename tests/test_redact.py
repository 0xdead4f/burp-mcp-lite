from burp_mcp_lite.format.redact import apply_redaction, redact_value


def test_redacts_known_names():
    assert redact_value("Authorization", "Bearer xyz") == "<redacted 10c>"
    assert redact_value("authorization", "Bearer xyz") == "<redacted 10c>"
    assert redact_value("Cookie", "a=1; b=2") == "<redacted 8c>"
    assert redact_value("Set-Cookie", "session=abc") == "<redacted 11c>"
    assert redact_value("X-Api-Key", "k") == "<redacted 1c>"


def test_passes_through_unknown():
    assert redact_value("Content-Type", "application/json") == "application/json"
    assert redact_value("Host", "x.example.com") == "x.example.com"


def test_empty_value():
    assert redact_value("Authorization", "") == "<redacted>"


def test_apply_redaction_off():
    headers = [("Authorization", "Bearer x"), ("Host", "h")]
    assert apply_redaction(headers, redact=False) == headers


def test_apply_redaction_on():
    headers = [("Authorization", "Bearer x"), ("Host", "h")]
    out = apply_redaction(headers, redact=True)
    assert out == [("Authorization", "<redacted 8c>"), ("Host", "h")]
