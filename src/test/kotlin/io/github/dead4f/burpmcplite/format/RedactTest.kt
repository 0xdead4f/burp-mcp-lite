package io.github.dead4f.burpmcplite.format

import kotlin.test.Test
import kotlin.test.assertEquals

class RedactTest {

    @Test fun `non-auth headers pass through untouched`() {
        assertEquals("text/html", Redact.redactValue("Content-Type", "text/html"))
        assertEquals("42", Redact.redactValue("Content-Length", "42"))
    }

    @Test fun `Authorization value becomes length stub`() {
        val v = "Bearer abcdef.ghijkl.mnopqr"
        assertEquals("<redacted ${v.length}c>", Redact.redactValue("Authorization", v))
    }

    @Test fun `Cookie value redacted regardless of case`() {
        val v = "session=abc; csrf=xyz"
        assertEquals("<redacted ${v.length}c>", Redact.redactValue("cookie", v))
        assertEquals("<redacted ${v.length}c>", Redact.redactValue("COOKIE", v))
    }

    @Test fun `empty redactable value yields generic stub`() {
        assertEquals("<redacted>", Redact.redactValue("Authorization", ""))
    }

    @Test fun `apply false leaves headers untouched`() {
        val h = listOf("Authorization" to "Bearer x", "Content-Type" to "text/plain")
        assertEquals(h, Redact.apply(h, redact = false))
    }

    @Test fun `apply true redacts matching headers only`() {
        val h = listOf("Authorization" to "Bearer x", "Content-Type" to "text/plain")
        val out = Redact.apply(h, redact = true)
        assertEquals("<redacted 8c>", out[0].second)
        assertEquals("text/plain", out[1].second)
    }
}
