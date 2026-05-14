package io.github.dead4f.burpmcplite.format

import kotlin.test.Test
import kotlin.test.assertEquals

class HttpParseTest {

    @Test fun `parses CRLF request`() {
        val raw = "GET /foo?x=1 HTTP/1.1\r\nHost: example.com\r\nUser-Agent: rspec\r\n\r\n"
        val r = HttpParse.parseRequest(raw)
        assertEquals("GET", r.method)
        assertEquals("/foo?x=1", r.target)
        assertEquals("HTTP/1.1", r.httpVersion)
        assertEquals("example.com", r.host)
        assertEquals("/foo?x=1", r.path)
        assertEquals(2, r.headers.size)
        assertEquals("", r.body)
    }

    @Test fun `parses LF-only response with reason phrase containing spaces`() {
        val raw = "HTTP/1.1 502 Bad Gateway\nContent-Type: text/html\nContent-Length: 5\n\nhello"
        val r = HttpParse.parseResponse(raw)
        assertEquals(502, r.status)
        assertEquals("Bad Gateway", r.reason)
        assertEquals("text/html", r.contentType)
        assertEquals(5, r.contentLength)
        assertEquals("hello", r.body)
    }

    @Test fun `absolute-form request target yields path-only path`() {
        val raw = "GET https://api.example.com/v1/items HTTP/1.1\r\nHost: api.example.com\r\n\r\n"
        val r = HttpParse.parseRequest(raw)
        assertEquals("/v1/items", r.path)
    }

    @Test fun `malformed header line keeps name with empty value`() {
        val raw = "GET / HTTP/1.1\r\nHost: x\r\nNonsense\r\n\r\n"
        val r = HttpParse.parseRequest(raw)
        val nonsense = r.headers.first { it.first == "Nonsense" }
        assertEquals("", nonsense.second)
    }

    @Test fun `content-type strips parameters and lowercases`() {
        val raw = "HTTP/1.1 200 OK\r\nContent-Type: Application/JSON; charset=UTF-8\r\n\r\n{}"
        val r = HttpParse.parseResponse(raw)
        assertEquals("application/json", r.contentType)
    }
}
