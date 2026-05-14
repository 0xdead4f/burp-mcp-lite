package io.github.dead4f.burpmcplite.tools

import io.github.dead4f.burpmcplite.filters.Filters
import io.github.dead4f.burpmcplite.format.Render
import io.github.dead4f.burpmcplite.snapshot.HistoryEntry
import io.github.dead4f.burpmcplite.snapshot.HistorySource
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonPrimitive

@Serializable
data class ListHistoryArgs(
    val limit: Int? = null,
    val offset: Int? = null,
    val fields: List<String>? = null,
    val host: String? = null,
    val path: String? = null,
    /** String or array of strings. We decode loosely as JsonElement. */
    val method: JsonElement? = null,
    val status: String? = null,
    val mime: String? = null,
    val match: String? = null,
    @SerialName("match_in") val matchIn: String? = null,
    val order: String? = null,
    val format: String? = null,
)

object ListHistoryTool {
    fun run(source: HistorySource, args: ListHistoryArgs): String {
        val limit = args.limit ?: 20
        val offset = args.offset ?: 0
        val order = (args.order ?: "latest").lowercase()
        val format = (args.format ?: "text").lowercase()

        val methodArg: Any? = args.method?.let { el ->
            when (el) {
                is JsonPrimitive -> el.content
                else -> runCatching { el.jsonArray.map { it.jsonPrimitive.content } }.getOrNull()
            }
        }
        val filter = Filters.build(
            Filters.BuildArgs(
                host = args.host,
                path = args.path,
                method = methodArg,
                status = args.status,
                mime = args.mime,
                match = args.match,
                matchIn = args.matchIn ?: Filters.MatchTarget.ResponseBody.key,
            )
        )

        val filtered: List<HistoryEntry> = source.entries().filter(filter).toList()
        val ordered = if (order == "latest") filtered.asReversed() else filtered
        val total = ordered.size
        val page = ordered.drop(offset).take(maxOf(0, limit))

        val active = if (args.fields == null) {
            Render.DEFAULT_FIELDS
        } else {
            val parsed = args.fields.mapNotNull { Render.parseField(it) }
            if (parsed.isEmpty()) Render.DEFAULT_FIELDS else parsed
        }

        return if (format == "json")
            Render.renderHistoryNdjson(page, active)
        else
            Render.renderHistoryTable(page, active, total, offset)
    }
}
