package io.github.dead4f.burpmcplite.tools

import io.github.dead4f.burpmcplite.snapshot.HistorySource
import io.github.dead4f.burpmcplite.snapshot.SiteMapSource
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject

/**
 * Single source of truth for the six MCP tools — name, description,
 * JSON Schema, and a handler that takes the raw arguments object and
 * returns a tool result.
 *
 * Both transports use this:
 *   - SSE (`/sse`) wraps each entry as an MCP-SDK `addTool(...)` call so
 *     the SDK's Protocol handles initialize / tools/list / tools/call.
 *   - Streamable HTTP (`/mcp`) dispatches JSON-RPC directly against this
 *     registry — see [io.github.dead4f.burpmcplite.server.StreamableHttp].
 *
 * The handler returns either a text payload (success) or a structured
 * error string with `isError = true` set on [ToolCallResult].
 */
data class ToolDef(
    val name: String,
    val description: String,
    val inputSchema: JsonObject,
    val handler: (JsonObject) -> ToolCallResult,
)

data class ToolCallResult(val text: String, val isError: Boolean = false) {
    companion object {
        fun ok(text: String) = ToolCallResult(text, isError = false)
        fun err(t: Throwable) = ToolCallResult(
            "error: ${t.javaClass.simpleName}: ${t.message ?: ""}",
            isError = true,
        )
    }
}

object ToolRegistry {
    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    fun build(source: HistorySource, siteMap: SiteMapSource): List<ToolDef> = listOf(
        ToolDef(
            name = "list_history",
            description =
                "Browse Burp proxy history with field projection and filters. " +
                    "Returns a compact text table by default. Use this for Recon " +
                    "(what was captured?), Filter (only POSTs that 4xx'd), and " +
                    "Cross-search (regex over response bodies via match=).",
            inputSchema = listHistorySchema(),
        ) { args ->
            try {
                val parsed = json.decodeFromJsonElement(ListHistoryArgs.serializer(), args)
                ToolCallResult.ok(ListHistoryTool.run(source, parsed))
            } catch (t: Throwable) { ToolCallResult.err(t) }
        },
        ToolDef(
            name = "view_request",
            description =
                "View one request by id (from list_history). Headers and cookies " +
                    "are OFF by default — pass include_headers=true if you need them. " +
                    "Auth header values are redacted by default; pass redact=false " +
                    "for the raw bytes. Body slicing: \"full\", \"none\", \"head:N\", " +
                    "\"tail:N\", or \"/regex/\".",
            inputSchema = viewRequestSchema(),
        ) { args ->
            try {
                val parsed = json.decodeFromJsonElement(ViewRequestArgs.serializer(), args)
                ToolCallResult.ok(ViewTool.runRequest(source, parsed))
            } catch (t: Throwable) { ToolCallResult.err(t) }
        },
        ToolDef(
            name = "view_response",
            description =
                "View one response by id. Same toggles as view_request. Default " +
                    "body=\"auto\" prints the full body if <4KB, else head:20 + a " +
                    "marker. Pass body=\"full\" to override.",
            inputSchema = viewResponseSchema(),
        ) { args ->
            try {
                val parsed = json.decodeFromJsonElement(ViewResponseArgs.serializer(), args)
                ToolCallResult.ok(ViewTool.runResponse(source, parsed))
            } catch (t: Throwable) { ToolCallResult.err(t) }
        },
        ToolDef(
            name = "match",
            description =
                "Predicate: does the entry match a regex? Returns \"matched: " +
                    "true/false\" plus a small evidence snippet — never the full " +
                    "body. Use this when you want to verify presence of a token/" +
                    "value without dumping the response into context.",
            inputSchema = matchSchema(),
        ) { args ->
            try {
                val parsed = json.decodeFromJsonElement(MatchArgs.serializer(), args)
                ToolCallResult.ok(MatchTool.run(source, parsed))
            } catch (t: Throwable) { ToolCallResult.err(t) }
        },
        ToolDef(
            name = "endpoints",
            description =
                "Deduplicated method+host+path inventory across history, with " +
                    "hit counts. Query strings are stripped for dedup. Filters: " +
                    "host, path, method.",
            inputSchema = endpointsSchema(),
        ) { args ->
            try {
                val parsed = json.decodeFromJsonElement(EndpointsArgs.serializer(), args)
                ToolCallResult.ok(EndpointsTool.run(source, parsed))
            } catch (t: Throwable) { ToolCallResult.err(t) }
        },
        ToolDef(
            name = "sitemap",
            description =
                "Browse Burp's site map (spider + scanner + proxy). " +
                    "mode=\"domains\" (default) lists unique hosts only. " +
                    "mode=\"entries\" needs domain= (or prefix=) and lists endpoints " +
                    "under that host: dedup=true (default) groups by method+path " +
                    "with last-seen status/mime + hit count; dedup=false flat-lists " +
                    "method/status/path per entry. Host column is omitted in " +
                    "entries mode — the domain is implicit.",
            inputSchema = sitemapSchema(),
        ) { args ->
            try {
                val parsed = json.decodeFromJsonElement(SiteMapArgs.serializer(), args)
                ToolCallResult.ok(SiteMapTool.run(siteMap, parsed))
            } catch (t: Throwable) { ToolCallResult.err(t) }
        },
        ToolDef(
            name = "stats",
            description = "Counts by method, status class, and top hosts across current Burp history.",
            inputSchema = emptyObjectSchema(),
        ) { _ ->
            try { ToolCallResult.ok(StatsTool.run(source, StatsArgs())) }
            catch (t: Throwable) { ToolCallResult.err(t) }
        },
    )

    // ---- JSON Schemas ----
    private fun s(type: String): JsonObject = buildJsonObject { put("type", JsonPrimitive(type)) }
    private fun strEnum(vararg vals: String): JsonObject = buildJsonObject {
        put("type", JsonPrimitive("string"))
        put("enum", JsonArray(vals.map { JsonPrimitive(it) }))
    }
    private fun arrOf(items: JsonElement): JsonObject = buildJsonObject {
        put("type", JsonPrimitive("array"))
        put("items", items)
    }
    private fun oneOfStringOrArray(): JsonObject = buildJsonObject {
        put("oneOf", JsonArray(listOf(s("string"), arrOf(s("string")))))
    }
    private fun emptyObjectSchema(): JsonObject = buildJsonObject {
        put("type", JsonPrimitive("object"))
        put("properties", buildJsonObject { })
    }

    private fun schema(properties: JsonObject, required: List<String> = emptyList()): JsonObject = buildJsonObject {
        put("type", JsonPrimitive("object"))
        put("properties", properties)
        if (required.isNotEmpty()) put("required", JsonArray(required.map { JsonPrimitive(it) }))
    }

    private fun listHistorySchema(): JsonObject = schema(
        properties = buildJsonObject {
            put("limit", s("integer"))
            put("offset", s("integer"))
            put("fields", arrOf(strEnum("id", "method", "status", "host", "path", "len", "mime", "time")))
            put("host", s("string"))
            put("path", s("string"))
            put("method", oneOfStringOrArray())
            put("status", s("string"))
            put("mime", s("string"))
            put("match", s("string"))
            put("match_in", strEnum(
                "request.body", "request.headers", "request.all",
                "response.body", "response.headers", "response.all",
            ))
            put("order", strEnum("latest", "oldest"))
            put("format", strEnum("text", "json"))
        },
    )

    private fun viewRequestSchema(): JsonObject = schema(
        properties = buildJsonObject {
            put("id", s("integer"))
            put("include_headers", s("boolean"))
            put("include_cookies", s("boolean"))
            put("redact", s("boolean"))
            put("body", s("string"))
            put("context", s("integer"))
        },
        required = listOf("id"),
    )

    private fun viewResponseSchema(): JsonObject = schema(
        properties = buildJsonObject {
            put("id", s("integer"))
            put("include_headers", s("boolean"))
            put("include_set_cookie", s("boolean"))
            put("redact", s("boolean"))
            put("body", s("string"))
            put("context", s("integer"))
        },
        required = listOf("id"),
    )

    private fun matchSchema(): JsonObject = schema(
        properties = buildJsonObject {
            put("id", s("integer"))
            put("pattern", s("string"))
            put("target", strEnum(
                "request.body", "request.headers", "request.all",
                "response.body", "response.headers", "response.all",
            ))
            put("case_sensitive", s("boolean"))
            put("context", s("integer"))
            put("max_hits", s("integer"))
        },
        required = listOf("id", "pattern"),
    )

    private fun sitemapSchema(): JsonObject = schema(
        properties = buildJsonObject {
            put("mode", strEnum("domains", "entries"))
            put("domain", s("string"))
            put("dedup", s("boolean"))
            put("path", s("string"))
            put("method", oneOfStringOrArray())
            put("status", s("string"))
            put("mime", s("string"))
            put("match", s("string"))
            put("match_in", strEnum(
                "request.body", "request.headers", "request.all",
                "response.body", "response.headers", "response.all",
            ))
            put("limit", s("integer"))
            put("offset", s("integer"))
            put("format", strEnum("text", "json"))
            put("prefix", s("string"))
        },
    )

    private fun endpointsSchema(): JsonObject = schema(
        properties = buildJsonObject {
            put("host", s("string"))
            put("path", s("string"))
            put("method", oneOfStringOrArray())
        },
    )
}
