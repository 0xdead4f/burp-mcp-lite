package io.github.dead4f.burpmcplite.tools

import io.github.dead4f.burpmcplite.snapshot.FakeHistorySource
import io.github.dead4f.burpmcplite.snapshot.HistorySource
import io.github.dead4f.burpmcplite.snapshot.RawEntry
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * End-to-end smoke through the tool functions on an in-memory source.
 * Asserts that defaults stay quiet (headers off, auth redacted, body=auto
 * truncated) and that each tool returns something sensible.
 */
class ToolsSmokeTest {

    private fun raw(
        method: String, path: String, status: Int,
        respCt: String = "text/plain", respBody: String = "ok",
        reqAuthValue: String? = null,
    ): RawEntry {
        val auth = if (reqAuthValue != null) "Authorization: $reqAuthValue\r\n" else ""
        val req = "$method $path HTTP/1.1\r\nHost: example.com\r\nUser-Agent: t\r\n$auth\r\n"
        val resp = "HTTP/1.1 $status OK\r\nContent-Type: $respCt\r\nContent-Length: ${respBody.length}\r\n\r\n$respBody"
        return RawEntry(req, resp, null)
    }

    private fun source(vararg entries: RawEntry): HistorySource =
        FakeHistorySource.of(*entries)

    @Test fun `list_history default table shows id method status host path len`() {
        val s = source(raw("GET", "/a", 200), raw("POST", "/b", 404))
        val out = ListHistoryTool.run(s, ListHistoryArgs())
        assertTrue(out.contains("id"))
        assertTrue(out.contains("method"))
        assertTrue(out.contains("status"))
        assertTrue(out.contains("/a"))
        assertTrue(out.contains("/b"))
        assertTrue(out.contains("-- 2 of 2"))
    }

    @Test fun `list_history filters by status class`() {
        val s = source(raw("GET", "/a", 200), raw("POST", "/b", 404), raw("PUT", "/c", 500))
        val out = ListHistoryTool.run(s, ListHistoryArgs(status = "4xx,5xx"))
        assertFalse(out.contains("/a"))
        assertTrue(out.contains("/b"))
        assertTrue(out.contains("/c"))
    }

    @Test fun `view_request default hides headers and body shows full text`() {
        val s = source(raw("GET", "/a", 200, reqAuthValue = "Bearer secret"))
        val out = ViewTool.runRequest(s, ViewRequestArgs(id = 0))
        // Header line is hidden by default
        assertFalse(out.contains("User-Agent:"))
        assertFalse(out.contains("Bearer secret"))
        // First-line summary is present
        assertTrue(out.contains("[0] GET https://example.com/a"))
    }

    @Test fun `view_request with include_headers and default redact masks Authorization`() {
        val s = source(raw("GET", "/a", 200, reqAuthValue = "Bearer secret"))
        val out = ViewTool.runRequest(s, ViewRequestArgs(id = 0, includeHeaders = true))
        assertTrue(out.contains("User-Agent: t"))
        assertFalse(out.contains("Bearer secret"))
        assertTrue(out.contains("<redacted"))
    }

    @Test fun `view_response auto truncates large bodies and notes override`() {
        val body = "x".repeat(8 * 1024)
        val s = source(raw("GET", "/big", 200, respBody = body))
        val out = ViewTool.runResponse(s, ViewResponseArgs(id = 0))
        assertTrue(out.contains("auto-truncated"))
        assertTrue(out.contains("pass body=\"full\""))
    }

    @Test fun `match returns matched plus bounded snippet`() {
        val s = source(raw("GET", "/a", 200, respBody = "alpha\nNEEDLE here\ngamma"))
        val out = MatchTool.run(s, MatchArgs(id = 0, pattern = "NEEDLE"))
        assertTrue(out.startsWith("matched: true"))
        assertTrue(out.contains("[L2]"))
    }

    @Test fun `endpoints groups by method+host+path and counts`() {
        val s = source(
            raw("GET", "/a", 200), raw("GET", "/a", 200), raw("GET", "/b", 200),
        )
        val out = EndpointsTool.run(s, EndpointsArgs())
        assertTrue(out.contains("/a"))
        assertTrue(out.contains("×2"))
        assertTrue(out.contains("×1"))
    }

    @Test fun `stats aggregates method and status class`() {
        val s = source(raw("GET", "/a", 200), raw("POST", "/b", 404))
        val out = StatsTool.run(s, StatsArgs())
        assertTrue(out.contains("by method: GET=1, POST=1"))
        assertTrue(out.contains("2xx=1"))
        assertTrue(out.contains("4xx=1"))
    }
}
