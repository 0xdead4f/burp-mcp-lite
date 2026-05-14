package io.github.dead4f.burpmcplite.server

import burp.api.montoya.logging.Logging
import io.github.dead4f.burpmcplite.tools.ToolDef
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.Application
import io.ktor.server.application.call
import io.ktor.server.request.receiveText
import io.ktor.server.response.respond
import io.ktor.server.response.respondText
import io.ktor.server.routing.delete
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.routing
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject

/**
 * Mounts the Streamable HTTP MCP endpoint at `/mcp` so the extension is
 * installable via:
 *
 *     claude mcp add --transport http burp-mcp-lite http://127.0.0.1:9876/mcp
 *
 * Stateless: each POST is dispatched on its own. We don't allocate a
 * `Mcp-Session-Id` because we have no session state (no resources/prompts
 * subscriptions, no per-client tool registry). Clients that demand a
 * session header (some implementations do) get a stable one back anyway.
 *
 * GET is 405 — we don't open a server→client SSE stream here; if you want
 * that, use the `/sse` endpoint mounted alongside us.
 */
object StreamableHttp {

    private const val STABLE_SESSION_ID: String = "burp-mcp-lite"

    fun install(
        app: Application,
        registry: List<ToolDef>,
        serverName: String,
        serverVersion: String,
        logging: Logging? = null,
    ) {
        val json = Json { ignoreUnknownKeys = true; isLenient = true; encodeDefaults = false }

        app.routing {
            post("/mcp") {
                val body = call.receiveText()
                val message: JsonElement = try {
                    json.parseToJsonElement(body)
                } catch (t: Throwable) {
                    logging?.logToOutput("[burp-mcp-lite] /mcp parse error: ${t.message}")
                    call.respondText(
                        Json.encodeToString(
                            JsonObject.serializer(),
                            kotlinx.serialization.json.buildJsonObject {
                                put("jsonrpc", kotlinx.serialization.json.JsonPrimitive("2.0"))
                                put("id", kotlinx.serialization.json.JsonNull)
                                put("error", kotlinx.serialization.json.buildJsonObject {
                                    put("code", kotlinx.serialization.json.JsonPrimitive(-32700))
                                    put("message", kotlinx.serialization.json.JsonPrimitive("Parse error"))
                                })
                            },
                        ),
                        contentType = io.ktor.http.ContentType.Application.Json,
                        status = HttpStatusCode.OK,
                    )
                    return@post
                }

                val result = JsonRpc.dispatch(message, registry, serverName, serverVersion)
                call.response.headers.append("Mcp-Session-Id", STABLE_SESSION_ID)

                val response = result.response
                if (response == null) {
                    // Notification — no response body.
                    call.respond(HttpStatusCode.Accepted)
                } else {
                    call.respondText(
                        json.encodeToString(JsonObject.serializer(), response),
                        contentType = io.ktor.http.ContentType.Application.Json,
                        status = HttpStatusCode.OK,
                    )
                }
            }

            // We don't push server-initiated messages, so an SSE long-poll on GET
            // would just dangle. Tell the client.
            get("/mcp") {
                call.response.headers.append("Allow", "POST, DELETE")
                call.respond(HttpStatusCode.MethodNotAllowed)
            }

            // Session termination is a no-op for our stateless server, but we
            // accept the request so clients that send it on shutdown aren't
            // surprised by a 405.
            delete("/mcp") {
                call.respond(HttpStatusCode.NoContent)
            }
        }
    }
}
