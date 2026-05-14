package io.github.dead4f.burpmcplite.format

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class SliceTest {

    @Test fun `full keeps body intact`() {
        val r = Slice.sliceBody("hello\nworld", "full")
        assertEquals("hello\nworld", r.text)
        assertEquals(false, r.truncated)
        assertEquals(2, r.totalLines)
    }

    @Test fun `none yields empty`() {
        val r = Slice.sliceBody("hello\nworld", "none")
        assertEquals("", r.text)
        assertTrue(r.truncated)
    }

    @Test fun `head N trims correctly and reports truncation`() {
        val r = Slice.sliceBody("a\nb\nc\nd", "head:2")
        assertEquals("a\nb", r.text)
        assertTrue(r.truncated)
        assertEquals(4, r.totalLines)
    }

    @Test fun `tail N trims correctly and reports truncation`() {
        val r = Slice.sliceBody("a\nb\nc\nd", "tail:2")
        assertEquals("c\nd", r.text)
        assertTrue(r.truncated)
    }

    @Test fun `regex slicing keeps matched and context lines`() {
        val body = "alpha\nbeta\ngamma\ndelta"
        val r = Slice.sliceBody(body, "/beta/", context = 1)
        // L1 + L2 + L3 kept (context=1 around L2)
        assertTrue(r.text.contains("[L1] alpha"))
        assertTrue(r.text.contains("[L2] beta"))
        assertTrue(r.text.contains("[L3] gamma"))
        assertEquals(1, r.hitCount)
        assertTrue(r.truncated) // delta dropped
    }

    @Test fun `windowedLine caps to ~240 chars centered on match`() {
        val line = "x".repeat(1000) + "NEEDLE" + "y".repeat(1000)
        val rx = Regex("NEEDLE")
        val out = Slice.windowedLine(line, rx, cap = 60)
        assertTrue(out.length <= 62, "got ${out.length}: '$out'") // cap + 2 ellipsis
        assertTrue(out.contains("NEEDLE"))
        assertTrue(out.startsWith("…") && out.endsWith("…"))
    }

    @Test fun `windowedLine head-trims when no match given`() {
        val line = "x".repeat(1000)
        val out = Slice.windowedLine(line, null, cap = 60)
        assertEquals(60, out.length)
        assertTrue(out.endsWith("…"))
    }

    @Test fun `invalid regex returns the marker`() {
        val r = Slice.sliceBody("a\nb", "/(unclosed/")
        assertTrue(r.text.startsWith("<invalid regex"))
        assertTrue(r.truncated)
    }

    @Test fun `unknown spec falls back to full not surprise`() {
        val r = Slice.sliceBody("a\nb", "bogus")
        assertEquals("a\nb", r.text)
        assertEquals(false, r.truncated)
        assertNotEquals("", r.text)
    }
}
