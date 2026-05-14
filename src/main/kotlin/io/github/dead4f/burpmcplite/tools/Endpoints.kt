package io.github.dead4f.burpmcplite.tools

import io.github.dead4f.burpmcplite.filters.Filters
import io.github.dead4f.burpmcplite.format.Render
import io.github.dead4f.burpmcplite.snapshot.HistorySource
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonPrimitive

@Serializable
data class EndpointsArgs(
    val host: String? = null,
    val path: String? = null,
    val method: JsonElement? = null,
)

object EndpointsTool {
    fun run(source: HistorySource, args: EndpointsArgs): String {
        val methodArg: Any? = args.method?.let { el ->
            when (el) {
                is JsonPrimitive -> el.content
                else -> runCatching { el.jsonArray.map { it.jsonPrimitive.content } }.getOrNull()
            }
        }
        val filter = Filters.build(Filters.BuildArgs(host = args.host, path = args.path, method = methodArg))

        val counts = mutableMapOf<String, Int>()
        val tuples = mutableMapOf<String, Triple<String, String, String>>()
        for (e in source.entries().filter(filter)) {
            val pathNoQuery = e.request.path.substringBefore('?')
            val key = "${e.request.method}|${e.request.host}|$pathNoQuery"
            counts.merge(key, 1, Int::plus)
            tuples.putIfAbsent(key, Triple(e.request.method, e.request.host, pathNoQuery))
        }
        val rows = counts.map { (k, c) ->
            val t = tuples.getValue(k)
            Render.EndpointRow(t.first, t.second, t.third, c)
        }.sortedWith(
            compareByDescending<Render.EndpointRow> { it.count }
                .thenBy { it.host }
                .thenBy { it.path },
        )
        return Render.renderEndpoints(rows)
    }
}
