package io.github.dead4f.burpmcplite.server

import io.github.dead4f.burpmcplite.snapshot.FakeHistorySource
import io.github.dead4f.burpmcplite.snapshot.RawEntry
import io.github.dead4f.burpmcplite.tools.ToolRegistry
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class JsonRpcTest {

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    private fun registry(): List<io.github.dead4f.burpmcplite.tools.ToolDef> {
        val raw = RawEntry(
            request = "GET /a HTTP/1.1\r\nHost: example.com\r\n\r\n",
            response = "HTTP/1.1 200 OK\r\nContent-Type: text/plain\r\nContent-Length: 2\r\n\r\nok",
            notes = null,
        )
        return ToolRegistry.build(FakeHistorySource.of(raw))
    }

    private fun dispatch(payload: String) =
        JsonRpc.dispatch(json.parseToJsonElement(payload), registry(), "burp-mcp-lite", "0.3.0")

    @Test fun `initialize returns server info and tools capability`() {
        val r = dispatch("""{"jsonrpc":"2.0","id":1,"method":"initialize","params":{"protocolVersion":"2025-06-18"}}""")
        val resp = r.response!!
        val result = resp["result"]!!.jsonObject
        assertEquals("2025-06-18", result["protocolVersion"]!!.jsonPrimitive.content)
        assertNotNull(result["capabilities"]!!.jsonObject["tools"])
        val info = result["serverInfo"]!!.jsonObject
        assertEquals("burp-mcp-lite", info["name"]!!.jsonPrimitive.content)
    }

    @Test fun `initialize falls back to supported version on unknown request`() {
        val r = dispatch("""{"jsonrpc":"2.0","id":1,"method":"initialize","params":{"protocolVersion":"9999-99-99"}}""")
        val v = r.response!!["result"]!!.jsonObject["protocolVersion"]!!.jsonPrimitive.content
        assertEquals(JsonRpc.SUPPORTED_PROTOCOL_VERSION, v)
    }

    @Test fun `notifications return no response`() {
        val r = dispatch("""{"jsonrpc":"2.0","method":"notifications/initialized"}""")
        assertNull(r.response)
    }

    @Test fun `tools list returns all six tools with name description schema`() {
        val r = dispatch("""{"jsonrpc":"2.0","id":2,"method":"tools/list"}""")
        val tools = r.response!!["result"]!!.jsonObject["tools"]!!.jsonArray
        assertEquals(6, tools.size)
        val names = tools.map { it.jsonObject["name"]!!.jsonPrimitive.content }.toSet()
        assertEquals(
            setOf("list_history", "view_request", "view_response", "match", "endpoints", "stats"),
            names,
        )
        // Every tool has a description and a schema.
        for (t in tools) {
            val obj = t.jsonObject
            assertTrue(obj["description"]!!.jsonPrimitive.content.isNotBlank())
            assertNotNull(obj["inputSchema"])
        }
    }

    @Test fun `tools call invokes the named handler`() {
        val r = dispatch("""{"jsonrpc":"2.0","id":3,"method":"tools/call","params":{"name":"stats","arguments":{}}}""")
        val result = r.response!!["result"]!!.jsonObject
        assertEquals(false, result["isError"]!!.jsonPrimitive.boolean)
        val text = result["content"]!!.jsonArray.first().jsonObject["text"]!!.jsonPrimitive.content
        assertTrue(text.contains("total entries"))
    }

    @Test fun `tools call with unknown name returns invalid params`() {
        val r = dispatch("""{"jsonrpc":"2.0","id":4,"method":"tools/call","params":{"name":"bogus","arguments":{}}}""")
        val err = r.response!!["error"]!!.jsonObject
        assertEquals(-32602, err["code"]!!.jsonPrimitive.content.toInt())
    }

    @Test fun `unknown method on a request returns method not found`() {
        val r = dispatch("""{"jsonrpc":"2.0","id":5,"method":"unknown/method"}""")
        val err = r.response!!["error"]!!.jsonObject
        assertEquals(-32601, err["code"]!!.jsonPrimitive.content.toInt())
    }

    @Test fun `unknown notification is silently dropped`() {
        val r = dispatch("""{"jsonrpc":"2.0","method":"notifications/unknown"}""")
        assertNull(r.response)
    }

    @Test fun `ping returns empty result`() {
        val r = dispatch("""{"jsonrpc":"2.0","id":6,"method":"ping"}""")
        val result = r.response!!["result"]!!.jsonObject
        assertTrue(result.isEmpty())
    }

    @Test fun `parse error path for missing method`() {
        val r = dispatch("""{"jsonrpc":"2.0","id":7}""")
        val err = r.response!!["error"]!!.jsonObject
        assertEquals(-32600, err["code"]!!.jsonPrimitive.content.toInt())
    }
}
