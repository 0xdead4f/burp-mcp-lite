package io.github.dead4f.burpmcplite.tools

import io.github.dead4f.burpmcplite.snapshot.FakeSiteMapSource
import io.github.dead4f.burpmcplite.snapshot.RawEntry
import kotlinx.serialization.json.JsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SiteMapToolTest {

    private fun raw(
        method: String,
        path: String,
        status: Int,
        host: String = "example.com",
        respCt: String = "text/html",
        respBody: String = "ok",
    ): RawEntry {
        val req = "$method $path HTTP/1.1\r\nHost: $host\r\n\r\n"
        val resp = "HTTP/1.1 $status OK\r\nContent-Type: $respCt\r\nContent-Length: ${respBody.length}\r\n\r\n$respBody"
        return RawEntry(req, resp, null)
    }

    // ---- domains mode --------------------------------------------------

    @Test fun `default mode lists domains with counts, sorted desc`() {
        val s = FakeSiteMapSource.of(
            raw("GET", "/a", 200, host = "api.example.com"),
            raw("GET", "/b", 200, host = "api.example.com"),
            raw("GET", "/c", 200, host = "api.example.com"),
            raw("GET", "/x", 200, host = "cdn.example.com"),
        )
        val out = SiteMapTool.run(s, SiteMapArgs())
        // No path or status columns — just host + count.
        assertTrue(out.contains("api.example.com"))
        assertTrue(out.contains("cdn.example.com"))
        assertTrue(out.contains("×3"))
        assertTrue(out.contains("×1"))
        assertFalse(out.contains("/a"))
        assertFalse(out.contains("/b"))
        assertFalse(out.contains("/x"))
        // Higher-count host appears first.
        val idxApi = out.indexOf("api.example.com")
        val idxCdn = out.indexOf("cdn.example.com")
        assertTrue(idxApi in 0..<idxCdn)
        assertTrue(out.contains("-- 2 of 2 (offset 0) --"))
    }

    @Test fun `domains mode json emits NDJSON of host+count only`() {
        val s = FakeSiteMapSource.of(
            raw("GET", "/a", 200, host = "api.example.com"),
            raw("GET", "/x", 200, host = "cdn.example.com"),
        )
        val out = SiteMapTool.run(s, SiteMapArgs(format = "json"))
        val lines = out.lines().filter { it.isNotBlank() }
        assertEquals(2, lines.size)
        assertTrue(lines.all { it.startsWith("{") && it.contains("\"host\":") && it.contains("\"count\":") })
        assertFalse(lines.any { it.contains("\"path\"") })
        assertFalse(lines.any { it.contains("\"status\"") })
    }

    @Test fun `domains mode on empty source emits friendly placeholder`() {
        val out = SiteMapTool.run(FakeSiteMapSource.of(), SiteMapArgs())
        assertTrue(out.contains("(no domains)"))
        assertTrue(out.contains("-- 0 of 0"))
    }

    // ---- entries mode --------------------------------------------------

    @Test fun `entries mode without scope errors out`() {
        val s = FakeSiteMapSource.of(raw("GET", "/a", 200))
        val out = SiteMapTool.run(s, SiteMapArgs(mode = "entries"))
        assertTrue(out.startsWith("error:"), "got: $out")
        assertTrue(out.contains("domain") || out.contains("prefix"))
    }

    @Test fun `entries mode dedups by method+path within the domain`() {
        val s = FakeSiteMapSource.of(
            raw("GET", "/a", 200, host = "api.example.com"),
            raw("GET", "/a", 200, host = "api.example.com"),
            raw("POST", "/a", 201, host = "api.example.com"),
            raw("GET", "/b", 404, host = "api.example.com"),
            raw("GET", "/x", 200, host = "cdn.example.com"), // out of scope
        )
        val out = SiteMapTool.run(s, SiteMapArgs(mode = "entries", domain = "api.example.com"))
        assertTrue(out.startsWith("domain: api.example.com"))
        assertTrue(out.contains("/a"))
        assertTrue(out.contains("/b"))
        assertFalse(out.contains("/x"))
        assertTrue(out.contains("×2")) // GET /a hit twice
        assertTrue(out.contains("×1")) // POST /a and GET /b each once
        // Status columns survived; host column omitted.
        assertTrue(out.contains("200"))
        assertTrue(out.contains("404"))
        assertFalse(out.contains("api.example.com  ")) // no host column padding
    }

    @Test fun `entries mode query strings stripped for dedup`() {
        val s = FakeSiteMapSource.of(
            raw("GET", "/x?a=1", 200, host = "api.example.com"),
            raw("GET", "/x?a=2", 200, host = "api.example.com"),
        )
        val out = SiteMapTool.run(s, SiteMapArgs(mode = "entries", domain = "api.example.com"))
        assertTrue(out.contains("×2"))
        assertFalse(out.contains("a=1"))
        assertFalse(out.contains("a=2"))
    }

    @Test fun `entries dedup=false emits flat method+status+path rows`() {
        val s = FakeSiteMapSource.of(
            raw("GET", "/a", 200, host = "api.example.com"),
            raw("GET", "/a", 200, host = "api.example.com"),
            raw("POST", "/b", 404, host = "api.example.com"),
        )
        val out = SiteMapTool.run(
            s,
            SiteMapArgs(mode = "entries", domain = "api.example.com", dedup = false),
        )
        assertTrue(out.startsWith("domain: api.example.com"))
        // Both /a rows should appear (no dedup).
        assertEquals(2, Regex("(?m)^GET").findAll(out).count())
        assertTrue(out.contains("POST"))
        assertTrue(out.contains("404"))
        // No mime column, no count column.
        assertFalse(out.contains("text/"))
        assertFalse(out.contains("×"))
        assertTrue(out.contains("-- 3 of 3 (offset 0) --"))
    }

    @Test fun `entries dedup=false honors limit and reports total`() {
        val raws = (1..10).map { raw("GET", "/p$it", 200, host = "api.example.com") }
        val s = FakeSiteMapSource.of(*raws.toTypedArray())
        val out = SiteMapTool.run(
            s,
            SiteMapArgs(mode = "entries", domain = "api.example.com", dedup = false, limit = 3),
        )
        assertTrue(out.contains("-- 3 of 10 (offset 0) --"))
    }

    @Test fun `entries domain without scheme is prefixed with https`() {
        val s = FakeSiteMapSource.of(
            raw("GET", "/keep", 200, host = "alpha.example.com"),
            raw("GET", "/drop", 200, host = "beta.example.com"),
        )
        val out = SiteMapTool.run(s, SiteMapArgs(mode = "entries", domain = "alpha.example.com"))
        assertTrue(out.contains("/keep"))
        assertFalse(out.contains("/drop"))
    }

    @Test fun `entries status filter applies on top of the domain scope`() {
        val s = FakeSiteMapSource.of(
            raw("GET", "/ok", 200, host = "api.example.com"),
            raw("GET", "/err", 500, host = "api.example.com"),
        )
        val out = SiteMapTool.run(
            s,
            SiteMapArgs(mode = "entries", domain = "api.example.com", status = "5xx"),
        )
        assertTrue(out.contains("/err"))
        assertFalse(out.contains("/ok"))
    }

    @Test fun `entries method filter accepts a single method`() {
        val s = FakeSiteMapSource.of(
            raw("GET", "/a", 200, host = "api.example.com"),
            raw("POST", "/b", 200, host = "api.example.com"),
        )
        val out = SiteMapTool.run(
            s,
            SiteMapArgs(mode = "entries", domain = "api.example.com", method = JsonPrimitive("POST")),
        )
        assertFalse(out.contains("/a"))
        assertTrue(out.contains("/b"))
    }

    @Test fun `entries dedup json drops host column`() {
        val s = FakeSiteMapSource.of(raw("GET", "/a", 200, host = "api.example.com"))
        val out = SiteMapTool.run(
            s,
            SiteMapArgs(mode = "entries", domain = "api.example.com", format = "json"),
        )
        val line = out.lines().first()
        assertTrue(line.contains("\"method\":\"GET\""))
        assertTrue(line.contains("\"path\":\"/a\""))
        assertFalse(line.contains("\"host\""))
    }

    @Test fun `entries flat json emits only method+status+path`() {
        val s = FakeSiteMapSource.of(raw("GET", "/a", 200, host = "api.example.com"))
        val out = SiteMapTool.run(
            s,
            SiteMapArgs(mode = "entries", domain = "api.example.com", dedup = false, format = "json"),
        )
        val line = out.lines().first()
        assertTrue(line.contains("\"method\":\"GET\""))
        assertTrue(line.contains("\"status\":200"))
        assertTrue(line.contains("\"path\":\"/a\""))
        assertFalse(line.contains("\"host\""))
        assertFalse(line.contains("\"mime\""))
        assertFalse(line.contains("\"count\""))
    }

    @Test fun `unknown mode is rejected`() {
        val s = FakeSiteMapSource.of(raw("GET", "/a", 200))
        val out = SiteMapTool.run(s, SiteMapArgs(mode = "bogus"))
        assertTrue(out.startsWith("error:"), "got: $out")
    }
}
