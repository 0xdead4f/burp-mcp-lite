package io.github.dead4f.burpmcplite.tools

import io.github.dead4f.burpmcplite.snapshot.BurpCollaboratorSource
import io.github.dead4f.burpmcplite.snapshot.CollaboratorInteraction
import io.github.dead4f.burpmcplite.snapshot.FakeCollaboratorSource
import io.github.dead4f.burpmcplite.snapshot.InteractionLog
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class CollaboratorToolTest {

    private fun hit(
        id: String = "pay000",
        type: String = "DNS",
        subtype: String? = "A",
        at: Long = 1_000,
        clientIp: String = "198.51.100.7",
        customData: String? = null,
        detail: String? = null,
    ) = CollaboratorInteraction(
        id = id,
        type = type,
        subtype = subtype,
        timestamp = Instant.ofEpochMilli(at),
        clientIp = clientIp,
        clientPort = 53,
        customData = customData,
        detail = detail,
    )

    // ---- collaborator_payload -------------------------------------------

    @Test fun `single payload renders payload and id`() {
        val s = FakeCollaboratorSource()
        val out = CollaboratorPayloadTool.run(s, CollaboratorPayloadArgs())
        assertTrue(out.contains("payload: pay000.oastify.com"), out)
        assertTrue(out.contains("id: pay000"), out)
        assertFalse(out.contains("custom_data"))
    }

    @Test fun `count mints N payloads in one table`() {
        val s = FakeCollaboratorSource()
        val out = CollaboratorPayloadTool.run(s, CollaboratorPayloadArgs(count = 3))
        assertTrue(out.startsWith("server: oastify.com"), out)
        assertTrue(out.contains("pay000.oastify.com"))
        assertTrue(out.contains("pay002.oastify.com"))
        assertTrue(out.contains("-- 3 payloads --"))
    }

    @Test fun `custom_data is indexed per payload when count is more than one`() {
        val s = FakeCollaboratorSource()
        val out = CollaboratorPayloadTool.run(
            s,
            CollaboratorPayloadArgs(count = 3, customData = "login"),
        )
        assertTrue(out.contains("login1"), out)
        assertTrue(out.contains("login2"), out)
        assertTrue(out.contains("login3"), out)
    }

    @Test fun `custom_data is used verbatim for a single payload`() {
        val s = FakeCollaboratorSource()
        val out = CollaboratorPayloadTool.run(s, CollaboratorPayloadArgs(customData = "login"))
        assertTrue(out.contains("custom_data: login"), out)
        assertFalse(out.contains("login1"))
    }

    @Test fun `indexed custom_data stays within Burp's 16 char ceiling`() {
        val tag = BurpCollaboratorSource.tagFor("abcdefghijklmnop", 9, 10)
        assertEquals(16, tag.length)
        assertTrue(tag.endsWith("10"))
    }

    @Test fun `non alphanumeric custom_data is rejected before hitting Burp`() {
        val out = CollaboratorPayloadTool.run(
            FakeCollaboratorSource(),
            CollaboratorPayloadArgs(customData = "log-in"),
        )
        assertTrue(out.startsWith("error:"), out)
        assertTrue(out.contains("alphanumeric"))
    }

    @Test fun `oversized custom_data is rejected`() {
        val out = CollaboratorPayloadTool.run(
            FakeCollaboratorSource(),
            CollaboratorPayloadArgs(customData = "a".repeat(17)),
        )
        assertTrue(out.startsWith("error:"), out)
        assertTrue(out.contains("16"))
    }

    @Test fun `count out of range is rejected`() {
        val s = FakeCollaboratorSource()
        assertTrue(CollaboratorPayloadTool.run(s, CollaboratorPayloadArgs(count = 0)).startsWith("error:"))
        assertTrue(CollaboratorPayloadTool.run(s, CollaboratorPayloadArgs(count = 99)).startsWith("error:"))
    }

    @Test fun `bare drops the server location`() {
        val s = FakeCollaboratorSource()
        val out = CollaboratorPayloadTool.run(s, CollaboratorPayloadArgs(bare = true))
        assertTrue(out.contains("payload: pay000"), out)
        assertFalse(out.contains("pay000.oastify.com"))
    }

    @Test fun `unavailable collaborator yields an actionable error, not a stack trace`() {
        val s = FakeCollaboratorSource().apply { unavailable = true }
        val out = CollaboratorPayloadTool.run(s, CollaboratorPayloadArgs())
        assertTrue(out.startsWith("error:"), out)
        assertTrue(out.contains("Professional"), out)
    }

    // ---- collaborator_log ------------------------------------------------

    @Test fun `empty log reports how many payloads were issued`() {
        val s = FakeCollaboratorSource()
        CollaboratorPayloadTool.run(s, CollaboratorPayloadArgs(count = 2))
        val out = CollaboratorLogTool.run(s, CollaboratorLogArgs())
        assertTrue(out.contains("(no interactions)"), out)
        assertTrue(out.contains("2 payload(s) issued"), out)
    }

    @Test fun `log table is compact and withholds raw capture by default`() {
        val s = FakeCollaboratorSource()
        s.push(hit(type = "HTTP", subtype = "HTTP_1", detail = "GET /x HTTP/1.1\r\nHost: pay000.oastify.com\r\n"))
        val out = CollaboratorLogTool.run(s, CollaboratorLogArgs())
        assertTrue(out.contains("time"))
        assertTrue(out.contains("HTTP"))
        assertTrue(out.contains("198.51.100.7"))
        assertTrue(out.contains("pay000"))
        assertFalse(out.contains("GET /x"), "raw capture must stay out of the default view")
        assertTrue(out.contains("detail=\"auto\""), out)
        assertTrue(out.contains("-- 1 of 1 (offset 0) --"))
    }

    @Test fun `detail auto emits the raw capture`() {
        val s = FakeCollaboratorSource()
        s.push(hit(type = "HTTP", subtype = "HTTP_1", detail = "GET /x HTTP/1.1\r\nHost: pay000.oastify.com\r\n"))
        val out = CollaboratorLogTool.run(s, CollaboratorLogArgs(detail = "auto"))
        assertTrue(out.contains("GET /x HTTP/1.1"), out)
        assertTrue(out.contains("[pay000 HTTP"), out)
    }

    @Test fun `detail auto truncates a large capture and says so`() {
        val s = FakeCollaboratorSource()
        s.push(hit(type = "HTTP", detail = (1..500).joinToString("\n") { "line $it padding padding" }))
        val out = CollaboratorLogTool.run(s, CollaboratorLogArgs(detail = "auto"))
        assertTrue(out.contains("auto-truncated"), out)
        assertTrue(out.contains("line 1 "))
        assertFalse(out.contains("line 400"))
    }

    @Test fun `dns query type shows in the type column, http version does not`() {
        val s = FakeCollaboratorSource()
        s.push(hit(type = "DNS", subtype = "AAAA"))
        s.push(hit(id = "pay001", type = "HTTP", subtype = "HTTP_2", at = 2_000))
        val out = CollaboratorLogTool.run(s, CollaboratorLogArgs())
        assertTrue(out.contains("DNS(AAAA)"), out)
        assertFalse(out.contains("HTTP(HTTP_2)"), out)
    }

    @Test fun `payload filter accepts id, hostname, url and email forms`() {
        val s = FakeCollaboratorSource()
        s.push(hit(id = "keepme"))
        s.push(hit(id = "dropme", at = 2_000))
        for (form in listOf(
            "keepme",
            "keepme.oastify.com",
            "https://keepme.oastify.com/callback?x=1",
            "victim@keepme.oastify.com",
        )) {
            val out = CollaboratorLogTool.run(s, CollaboratorLogArgs(payload = form))
            assertTrue(out.contains("keepme"), "form=$form → $out")
            assertFalse(out.contains("dropme"), "form=$form → $out")
        }
    }

    @Test fun `type filter accepts a comma list`() {
        val s = FakeCollaboratorSource()
        s.push(hit(id = "a", type = "DNS"))
        s.push(hit(id = "b", type = "HTTP", at = 2_000))
        s.push(hit(id = "c", type = "SMTP", at = 3_000))
        val out = CollaboratorLogTool.run(s, CollaboratorLogArgs(type = "http,smtp"))
        assertTrue(out.contains("HTTP"))
        assertTrue(out.contains("SMTP"))
        assertFalse(out.contains("DNS"))
        assertTrue(out.contains("-- 2 of 2 (offset 0) --"))
    }

    @Test fun `order defaults to latest first and can be flipped`() {
        val s = FakeCollaboratorSource()
        s.push(hit(id = "older", at = 1_000))
        s.push(hit(id = "newer", at = 2_000))
        val latest = CollaboratorLogTool.run(s, CollaboratorLogArgs())
        assertTrue(latest.indexOf("newer") < latest.indexOf("older"), latest)
        val oldest = CollaboratorLogTool.run(s, CollaboratorLogArgs(order = "oldest"))
        assertTrue(oldest.indexOf("older") < oldest.indexOf("newer"), oldest)
    }

    @Test fun `limit pages and reports the total`() {
        val s = FakeCollaboratorSource()
        repeat(5) { s.push(hit(id = "pay$it", at = (it + 1) * 1_000L)) }
        val out = CollaboratorLogTool.run(s, CollaboratorLogArgs(limit = 2))
        assertTrue(out.contains("-- 2 of 5 (offset 0) --"), out)
    }

    @Test fun `json format emits NDJSON without detail unless asked`() {
        val s = FakeCollaboratorSource()
        s.push(hit(type = "HTTP", detail = "GET /x HTTP/1.1"))
        val plain = CollaboratorLogTool.run(s, CollaboratorLogArgs(format = "json"))
        assertTrue(plain.startsWith("{"), plain)
        assertTrue(plain.contains("\"payload\":\"pay000\""))
        assertFalse(plain.contains("\"detail\""))

        val withDetail = CollaboratorLogTool.run(s, CollaboratorLogArgs(format = "json", detail = "full"))
        assertTrue(withDetail.contains("\"detail\":\"GET /x HTTP/1.1\""), withDetail)
    }

    @Test fun `custom data column is dropped when no hit carries a tag`() {
        val s = FakeCollaboratorSource()
        s.push(hit())
        assertFalse(CollaboratorLogTool.run(s, CollaboratorLogArgs()).contains("data"))
        s.push(hit(id = "pay001", at = 2_000, customData = "login1"))
        val out = CollaboratorLogTool.run(s, CollaboratorLogArgs())
        assertTrue(out.contains("login1"), out)
    }

    @Test fun `unavailable collaborator yields an actionable error from the log tool`() {
        val s = FakeCollaboratorSource().apply { unavailable = true }
        val out = CollaboratorLogTool.run(s, CollaboratorLogArgs())
        assertTrue(out.startsWith("error:"), out)
        assertTrue(out.contains("Professional"), out)
    }

    // ---- the accumulating log -------------------------------------------

    @Test fun `polling twice never loses an earlier hit`() {
        val s = FakeCollaboratorSource()
        s.push(hit(id = "first", at = 1_000))
        assertTrue(CollaboratorLogTool.run(s, CollaboratorLogArgs()).contains("first"))
        s.push(hit(id = "second", at = 2_000))
        val out = CollaboratorLogTool.run(s, CollaboratorLogArgs())
        assertTrue(out.contains("first"), out)
        assertTrue(out.contains("second"), out)
        assertTrue(out.contains("-- 2 of 2 (offset 0) --"), out)
    }

    @Test fun `a replayed poll does not duplicate rows`() {
        val log = InteractionLog()
        val batch = listOf(hit(id = "a", at = 1_000), hit(id = "b", at = 2_000))
        assertEquals(2, log.merge(batch))
        assertEquals(0, log.merge(batch), "same interactions must collapse")
        assertEquals(2, log.size())
    }

    @Test fun `same payload with different interaction types are separate rows`() {
        val log = InteractionLog()
        log.merge(
            listOf(
                hit(id = "a", type = "DNS", at = 1_000),
                hit(id = "a", type = "HTTP", at = 1_000),
            ),
        )
        assertEquals(2, log.size())
    }

    @Test fun `merge keeps arrival order`() {
        val log = InteractionLog()
        log.merge(listOf(hit(id = "a", at = 1_000)))
        log.merge(listOf(hit(id = "b", at = 2_000)))
        assertEquals(listOf("a", "b"), log.all().map { it.id })
    }

    @Test fun `payloadId normalizes every shape a payload gets pasted back in`() {
        assertEquals("abc123", CollaboratorLogTool.payloadId("abc123"))
        assertEquals("abc123", CollaboratorLogTool.payloadId("ABC123.oastify.com"))
        assertEquals("abc123", CollaboratorLogTool.payloadId("https://abc123.oastify.com/cb?x=1"))
        assertEquals("abc123", CollaboratorLogTool.payloadId("  victim@abc123.oastify.com  "))
    }
}
