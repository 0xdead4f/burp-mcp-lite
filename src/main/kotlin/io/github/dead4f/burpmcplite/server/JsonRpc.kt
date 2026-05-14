package io.github.dead4f.burpmcplite.server

import io.github.dead4f.burpmcplite.tools.ToolDef
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * Stateless JSON-RPC dispatcher for the Streamable HTTP `/mcp` endpoint.
 *
 * Each HTTP POST carries one JSON-RPC 2.0 message. We dispatch it against
 * the shared [ToolDef] registry and return either a response object or
 * `null` (meaning the input was a notification — no body, 202 Accepted).
 *
 * Methods supported:
 *   - `initialize`              → server info + capabilities
 *   - `notifications/initialized` → no response (notification)
 *   - `tools/list`              → registry as MCP Tool array
 *   - `tools/call`              → invoke the named handler
 *   - `ping`                    → empty result
 *
 * Anything else gets a `-32601 Method not found`. Anything that throws
 * inside the dispatcher gets a `-32603 Internal error`.
 *
 * The supported MCP protocol versions are intentionally a small fixed set —
 * if the client asks for one we know, we echo it back; otherwise we fall
 * through to our preferred version.
 */
object JsonRpc {

    const val SUPPORTED_PROTOCOL_VERSION: String = "2025-06-18"
    private val ACCEPTABLE_PROTOCOL_VERSIONS: Set<String> =
        setOf("2024-11-05", "2025-03-26", "2025-06-18")

    private const val PARSE_ERROR = -32700
    private const val INVALID_REQUEST = -32600
    private const val METHOD_NOT_FOUND = -32601
    private const val INVALID_PARAMS = -32602
    private const val INTERNAL_ERROR = -32603

    /**
     * Result of dispatching one message. [response] is `null` when the input
     * was a notification (Mcp-Session-Id header still set on the way out).
     */
    data class DispatchResult(val response: JsonObject?)

    fun dispatch(
        message: JsonElement,
        registry: List<ToolDef>,
        serverName: String,
        serverVersion: String,
    ): DispatchResult {
        val obj = message as? JsonObject
            ?: return DispatchResult(error(null, PARSE_ERROR, "Expected JSON-RPC object"))

        val id = obj["id"] // may be null (notification), string, or number
        val method = (obj["method"] as? JsonPrimitive)?.content
            ?: return DispatchResult(error(id, INVALID_REQUEST, "Missing method"))
        val params = obj["params"] as? JsonObject

        return try {
            when (method) {
                "initialize" -> DispatchResult(initialize(id, params, serverName, serverVersion))
                "notifications/initialized",
                "notifications/cancelled",
                "notifications/progress" -> DispatchResult(null) // notifications never have responses
                "tools/list" -> DispatchResult(toolsList(id, registry))
                "tools/call" -> DispatchResult(toolsCall(id, params, registry))
                "ping" -> DispatchResult(result(id, buildJsonObject { }))
                else -> {
                    // A notification we don't recognize still has no `id` and no response.
                    if (id == null) DispatchResult(null)
                    else DispatchResult(error(id, METHOD_NOT_FOUND, "Method not found: $method"))
                }
            }
        } catch (t: Throwable) {
            DispatchResult(error(id, INTERNAL_ERROR, "${t.javaClass.simpleName}: ${t.message ?: ""}"))
        }
    }

    private fun initialize(
        id: JsonElement?,
        params: JsonObject?,
        serverName: String,
        serverVersion: String,
    ): JsonObject {
        val requested = (params?.get("protocolVersion") as? JsonPrimitive)?.content
        val negotiated = if (requested != null && requested in ACCEPTABLE_PROTOCOL_VERSIONS) requested
        else SUPPORTED_PROTOCOL_VERSION

        return result(id, buildJsonObject {
            put("protocolVersion", JsonPrimitive(negotiated))
            put("capabilities", buildJsonObject {
                put("tools", buildJsonObject {
                    put("listChanged", JsonPrimitive(false))
                })
            })
            put("serverInfo", buildJsonObject {
                put("name", JsonPrimitive(serverName))
                put("version", JsonPrimitive(serverVersion))
            })
        })
    }

    private fun toolsList(id: JsonElement?, registry: List<ToolDef>): JsonObject {
        val arr = buildJsonArray {
            for (def in registry) {
                add(buildJsonObject {
                    put("name", JsonPrimitive(def.name))
                    put("description", JsonPrimitive(def.description))
                    put("inputSchema", def.inputSchema)
                })
            }
        }
        return result(id, buildJsonObject { put("tools", arr) })
    }

    private fun toolsCall(id: JsonElement?, params: JsonObject?, registry: List<ToolDef>): JsonObject {
        val name = (params?.get("name") as? JsonPrimitive)?.content
            ?: return error(id, INVALID_PARAMS, "tools/call requires params.name")
        val args = params["arguments"] as? JsonObject ?: JsonObject(emptyMap())
        val def = registry.firstOrNull { it.name == name }
            ?: return error(id, INVALID_PARAMS, "Unknown tool: $name")
        val res = def.handler(args)
        return result(id, buildJsonObject {
            put("content", buildJsonArray {
                add(buildJsonObject {
                    put("type", JsonPrimitive("text"))
                    put("text", JsonPrimitive(res.text))
                })
            })
            put("isError", JsonPrimitive(res.isError))
        })
    }

    private fun result(id: JsonElement?, payload: JsonObject): JsonObject = buildJsonObject {
        put("jsonrpc", JsonPrimitive("2.0"))
        put("id", id ?: JsonNull)
        put("result", payload)
    }

    private fun error(id: JsonElement?, code: Int, message: String): JsonObject = buildJsonObject {
        put("jsonrpc", JsonPrimitive("2.0"))
        put("id", id ?: JsonNull)
        put("error", buildJsonObject {
            put("code", JsonPrimitive(code))
            put("message", JsonPrimitive(message))
        })
    }

    /** True if the message is a JSON-RPC notification (no `id`). */
    fun isNotification(message: JsonElement): Boolean {
        val obj = message as? JsonObject ?: return false
        if (!obj.containsKey("method")) return false
        val id = obj["id"]
        return id == null || id is JsonNull
    }
}
