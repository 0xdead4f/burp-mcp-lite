package io.github.dead4f.burpmcplite.snapshot

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class HistorySourceTest {

    private fun raw(path: String) = RawEntry(
        request = "GET $path HTTP/1.1\r\nHost: example.com\r\n\r\n",
        response = "HTTP/1.1 200 OK\r\n\r\n",
        notes = null,
    )

    @Test fun `of() auto-numbers ids from zero`() {
        val s = FakeHistorySource.of(raw("/a"), raw("/b"), raw("/c"))
        val ids = s.entries().map { it.id }.toList()
        assertEquals(listOf(0, 1, 2), ids)
    }

    @Test fun `byId uses explicit id, not list position`() {
        val s = FakeHistorySource(mutableListOf(
            8000 to raw("/oldest-surviving"),
            40477 to raw("/middle"),
            40478 to raw("/newest"),
        ))
        // list position 0 is id=8000; list position 2 is id=40478
        assertEquals("/oldest-surviving", s.entries().first().request.path)
        assertEquals(40478, s.entries().last().id)
        assertEquals("/newest", s.byId(40478)?.request?.path)
        assertEquals("/middle", s.byId(40477)?.request?.path)
    }

    @Test fun `byId returns null for a missing id even if the position would be valid`() {
        val s = FakeHistorySource(mutableListOf(
            100 to raw("/a"),
            200 to raw("/b"),
        ))
        // No entry with id=0, even though list position 0 exists.
        assertNull(s.byId(0))
        assertNotNull(s.byId(100))
        assertNotNull(s.byId(200))
        assertNull(s.byId(150))
    }

    @Test fun `simulated eviction preserves ids of surviving entries`() {
        val s = FakeHistorySource(mutableListOf(
            1 to raw("/a"), 2 to raw("/b"), 3 to raw("/c"), 4 to raw("/d"),
        ))
        // Evict ids 1 and 2 (Burp retention).
        s.clear()
        s.add(3, raw("/c"))
        s.add(4, raw("/d"))
        s.add(5, raw("/e"))
        // /c keeps id 3, /d keeps id 4. /a and /b are gone.
        assertEquals("/c", s.byId(3)?.request?.path)
        assertEquals("/d", s.byId(4)?.request?.path)
        assertNull(s.byId(1))
        assertNull(s.byId(2))
        assertEquals(3, s.size())
    }
}
