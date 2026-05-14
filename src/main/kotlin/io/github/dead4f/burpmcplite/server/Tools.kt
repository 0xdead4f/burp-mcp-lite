package io.github.dead4f.burpmcplite.server

import io.github.dead4f.burpmcplite.tools.ToolDef
import io.modelcontextprotocol.kotlin.sdk.CallToolResult
import io.modelcontextprotocol.kotlin.sdk.TextContent
import io.modelcontextprotocol.kotlin.sdk.Tool
import io.modelcontextprotocol.kotlin.sdk.server.Server

/**
 * SSE-side adapter: installs each [ToolDef] in [registry] onto the MCP
 * SDK [Server] so that `mcp { server }` Ktor wiring serves them at `/sse`.
 * The same registry is dispatched directly by [StreamableHttp] at `/mcp`.
 */
fun Server.registerLiteTools(registry: List<ToolDef>) {
    for (def in registry) {
        val schema = Tool.Input(
            properties = (def.inputSchema["properties"] as? kotlinx.serialization.json.JsonObject)
                ?: kotlinx.serialization.json.JsonObject(emptyMap()),
            required = (def.inputSchema["required"] as? kotlinx.serialization.json.JsonArray)
                ?.map { (it as kotlinx.serialization.json.JsonPrimitive).content }
                ?: emptyList(),
        )
        addTool(
            name = def.name,
            description = def.description,
            inputSchema = schema,
        ) { req ->
            val res = def.handler(req.arguments)
            CallToolResult(content = listOf(TextContent(res.text)), isError = res.isError.takeIf { it })
        }
    }
}
