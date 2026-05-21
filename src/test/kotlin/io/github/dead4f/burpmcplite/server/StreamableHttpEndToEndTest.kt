package io.github.dead4f.burpmcplite.server

import io.github.dead4f.burpmcplite.snapshot.FakeHistorySource
import io.github.dead4f.burpmcplite.snapshot.FakeSiteMapSource
import io.github.dead4f.burpmcplite.snapshot.RawEntry
import io.github.dead4f.burpmcplite.tools.ToolRegistry
import io.ktor.client.HttpClient
import io.ktor.client.request.delete
import io.ktor.client.request.get
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.contentType
import io.ktor.server.testing.testApplication
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Wire-level test against the actual Ktor routes. Posts JSON-RPC at `/mcp`
 * and validates the HTTP envelope (method, status, headers, body shape).
 */
class StreamableHttpEndToEndTest {

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    private fun registry(): List<io.github.dead4f.burpmcplite.tools.ToolDef> {
        val raw = RawEntry(
            request = "GET /a HTTP/1.1\r\nHost: example.com\r\n\r\n",
            response = "HTTP/1.1 200 OK\r\nContent-Type: text/plain\r\nContent-Length: 2\r\n\r\nok",
            notes = null,
        )
        return ToolRegistry.build(FakeHistorySource.of(raw), FakeSiteMapSource.of(raw))
    }

    @Test fun `POST initialize returns negotiated protocol and session header`() = runTest {
        testApplication {
            application {
                StreamableHttp.install(this, registry(), "burp-mcp-lite", "0.3.1")
            }
            val resp = client.post("/mcp") {
                contentType(ContentType.Application.Json)
                setBody("""{"jsonrpc":"2.0","id":1,"method":"initialize","params":{"protocolVersion":"2025-06-18"}}""")
            }
            assertEquals(200, resp.status.value)
            assertEquals("burp-mcp-lite", resp.headers["Mcp-Session-Id"])
            val body = json.parseToJsonElement(resp.bodyAsText()).jsonObject
            val result = body["result"]!!.jsonObject
            assertEquals("2025-06-18", result["protocolVersion"]!!.jsonPrimitive.content)
        }
    }

    @Test fun `POST notification returns 202 with no body`() = runTest {
        testApplication {
            application {
                StreamableHttp.install(this, registry(), "burp-mcp-lite", "0.3.1")
            }
            val resp = client.post("/mcp") {
                contentType(ContentType.Application.Json)
                setBody("""{"jsonrpc":"2.0","method":"notifications/initialized"}""")
            }
            assertEquals(202, resp.status.value)
        }
    }

    @Test fun `POST tools call returns content array`() = runTest {
        testApplication {
            application {
                StreamableHttp.install(this, registry(), "burp-mcp-lite", "0.3.1")
            }
            val resp = client.post("/mcp") {
                contentType(ContentType.Application.Json)
                setBody("""{"jsonrpc":"2.0","id":7,"method":"tools/call","params":{"name":"stats","arguments":{}}}""")
            }
            assertEquals(200, resp.status.value)
            val body = json.parseToJsonElement(resp.bodyAsText()).jsonObject
            val content = body["result"]!!.jsonObject["content"]!!.jsonArray
            assertTrue(content.first().jsonObject["text"]!!.jsonPrimitive.content.contains("total entries"))
        }
    }

    @Test fun `GET on mcp is 405 with Allow header`() = runTest {
        testApplication {
            application {
                StreamableHttp.install(this, registry(), "burp-mcp-lite", "0.3.1")
            }
            val resp = client.get("/mcp")
            assertEquals(405, resp.status.value)
            assertEquals("POST, DELETE", resp.headers["Allow"])
        }
    }

    @Test fun `DELETE on mcp is 204`() = runTest {
        testApplication {
            application {
                StreamableHttp.install(this, registry(), "burp-mcp-lite", "0.3.1")
            }
            val resp = client.delete("/mcp")
            assertEquals(204, resp.status.value)
        }
    }

    @Test fun `garbage body produces parse error response`() = runTest {
        testApplication {
            application {
                StreamableHttp.install(this, registry(), "burp-mcp-lite", "0.3.1")
            }
            val resp = client.post("/mcp") {
                contentType(ContentType.Application.Json)
                setBody("not json at all{{{")
            }
            assertEquals(200, resp.status.value)
            val body = json.parseToJsonElement(resp.bodyAsText()).jsonObject
            assertEquals(-32700, body["error"]!!.jsonObject["code"]!!.jsonPrimitive.content.toInt())
        }
    }
}
