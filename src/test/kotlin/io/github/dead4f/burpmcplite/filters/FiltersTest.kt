package io.github.dead4f.burpmcplite.filters

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class FiltersTest {

    @Test fun `exact code matches only that code`() {
        val p = Filters.parseStatusFilter("404")
        assertTrue(p.test(404))
        assertFalse(p.test(403))
        assertFalse(p.test(500))
    }

    @Test fun `class token expands to range`() {
        val p = Filters.parseStatusFilter("2xx")
        assertTrue(p.test(200))
        assertTrue(p.test(299))
        assertFalse(p.test(300))
    }

    @Test fun `explicit range with classes works on both ends`() {
        val p = Filters.parseStatusFilter("200-3xx")
        assertTrue(p.test(200))
        assertTrue(p.test(399))
        assertFalse(p.test(400))
    }

    @Test fun `comma list ors the matchers`() {
        val p = Filters.parseStatusFilter("404,500-503")
        assertTrue(p.test(404))
        assertTrue(p.test(500))
        assertTrue(p.test(503))
        assertFalse(p.test(504))
        assertFalse(p.test(200))
    }

    @Test fun `unknown token fails closed`() {
        val p = Filters.parseStatusFilter("not-a-code")
        assertFalse(p.test(200)) // never-match, not pass-through
    }

    @Test fun `empty spec passes everything`() {
        val p = Filters.parseStatusFilter("")
        assertTrue(p.test(200))
        assertTrue(p.test(500))
    }

    @Test fun `match target parsing falls back to response body`() {
        assertEquals(Filters.MatchTarget.ResponseBody, Filters.MatchTarget.parse("garbage"))
        assertEquals(Filters.MatchTarget.RequestHeaders, Filters.MatchTarget.parse("request.headers"))
        assertEquals(Filters.MatchTarget.ResponseAll, Filters.MatchTarget.parse("RESPONSE.ALL"))
    }
}
