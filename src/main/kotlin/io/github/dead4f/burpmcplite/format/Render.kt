package io.github.dead4f.burpmcplite.format

import io.github.dead4f.burpmcplite.snapshot.HistoryEntry
import io.github.dead4f.burpmcplite.snapshot.entryTimestamp
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * Text rendering for tool outputs. Single source of truth for column widths,
 * headers, footers, and JSON variants. Tuned for token efficiency.
 */
object Render {

    enum class Field { id, method, status, host, path, len, mime, time }

    val DEFAULT_FIELDS: List<Field> = listOf(
        Field.id, Field.method, Field.status, Field.host, Field.path, Field.len,
    )
    val ALL_FIELDS: Set<Field> = Field.entries.toSet()

    private val COLUMN_CAP: Map<Field, Int> = mapOf(
        Field.host to 32,
        Field.path to 60,
        Field.mime to 24,
    )

    private val isoZ: DateTimeFormatter = DateTimeFormatter.ISO_INSTANT
    private val zone: ZoneId = ZoneId.systemDefault()

    fun parseField(name: String): Field? =
        try { Field.valueOf(name) } catch (_: Exception) { null }

    fun formatEntryTime(e: HistoryEntry): String {
        val instant = entryTimestamp(e)
        val zdt = instant.atZone(zone)
        val today = LocalDate.now(zone)
        val sameDay = zdt.toLocalDate() == today
        val hms = "%02d:%02d:%02d".format(zdt.hour, zdt.minute, zdt.second)
        return if (sameDay) hms else "%02d-%02d %s".format(zdt.monthValue, zdt.dayOfMonth, hms)
    }

    private fun humanSize(n: Int): String = when {
        n <= 0 -> "0"
        n < 1024 -> n.toString()
        n < 1024 * 1024 -> "%.1fK".format(n / 1024.0)
        else -> "%.1fM".format(n / (1024.0 * 1024.0))
    }

    private fun cell(e: HistoryEntry, f: Field): String = when (f) {
        Field.id -> e.id.toString()
        Field.method -> e.request.method.ifEmpty { "-" }
        Field.status -> if (e.response.status > 0) e.response.status.toString() else "-"
        Field.host -> e.request.host.ifEmpty { "-" }
        Field.path -> e.request.path.ifEmpty { "-" }
        Field.len -> humanSize(e.response.contentLength)
        Field.mime -> e.response.contentType.ifEmpty { "-" }
        Field.time -> formatEntryTime(e)
    }

    private fun jsonCell(e: HistoryEntry, f: Field): JsonElement = when (f) {
        Field.id -> JsonPrimitive(e.id)
        Field.status -> JsonPrimitive(e.response.status)
        Field.len -> JsonPrimitive(e.response.contentLength)
        Field.method -> JsonPrimitive(e.request.method)
        Field.host -> JsonPrimitive(e.request.host)
        Field.path -> JsonPrimitive(e.request.path)
        Field.mime -> JsonPrimitive(e.response.contentType)
        Field.time -> JsonPrimitive(
            isoZ.format(entryTimestamp(e)).replace(Regex("\\.\\d{3}Z$"), "Z"),
        )
    }

    private fun truncate(s: String, n: Int): String {
        if (s.length <= n) return s
        if (n <= 1) return "…"
        return s.substring(0, n - 1) + "…"
    }

    fun renderHistoryTable(
        entries: List<HistoryEntry>,
        fields: List<Field>,
        total: Int,
        offset: Int,
    ): String {
        val cells: List<List<String>> = entries.map { e ->
            fields.map { f -> truncate(cell(e, f), COLUMN_CAP[f] ?: 64) }
        }
        val widths = fields.mapIndexed { i, f ->
            val header = f.name
            val maxCell = cells.maxOfOrNull { it[i].length } ?: 0
            maxOf(header.length, maxCell)
        }
        val lines = mutableListOf<String>()
        lines += fields.mapIndexed { i, f -> f.name.padEnd(widths[i]) }
            .joinToString("  ").trimEnd()
        for (row in cells) {
            lines += row.mapIndexed { i, c -> c.padEnd(widths[i]) }
                .joinToString("  ").trimEnd()
        }
        lines += "-- ${entries.size} of $total (offset $offset) --"
        return lines.joinToString("\n")
    }

    fun renderHistoryNdjson(entries: List<HistoryEntry>, fields: List<Field>): String =
        entries.joinToString("\n") { e ->
            val map = buildMap<String, JsonElement> {
                for (f in fields) put(f.name, jsonCell(e, f) ?: JsonNull)
            }
            JsonObject(map).toString()
        }

    /**
     * Burp's request-line is origin-form; scheme isn't preserved on the wire
     * after Montoya's `toString()`. Most proxy traffic is HTTPS — hardcode
     * rather than emit a misleading "?".
     */
    private fun schemeGuess(@Suppress("UNUSED_PARAMETER") e: HistoryEntry): String = "https"

    fun renderRequestView(
        e: HistoryEntry,
        headers: List<Header>,
        body: String,
        showHeaders: Boolean,
        note: String? = null,
    ): String {
        val parts = mutableListOf<String>()
        val scheme = schemeGuess(e)
        parts += "[${e.id}] ${e.request.method} $scheme://${e.request.host}${e.request.path}" +
            "  (${formatEntryTime(e)})"
        if (showHeaders && headers.isNotEmpty()) parts += headers.renderText()
        parts += ""
        parts += body.ifEmpty { "(no body)" }
        if (note != null) parts += note
        return parts.joinToString("\n")
    }

    fun renderResponseView(
        e: HistoryEntry,
        headers: List<Header>,
        body: String,
        showHeaders: Boolean,
        note: String? = null,
    ): String {
        val size = humanSize(e.response.contentLength)
        val ct = e.response.contentType.ifEmpty { "?" }
        val head = "[${e.id}] ${e.response.status} ${e.response.reason}".trimEnd() +
            "  ($size, $ct, ${formatEntryTime(e)})"
        val parts = mutableListOf(head)
        if (showHeaders && headers.isNotEmpty()) parts += headers.renderText()
        parts += ""
        parts += body.ifEmpty { "(no body)" }
        if (note != null) parts += note
        return parts.joinToString("\n")
    }

    fun renderMatch(matched: Boolean, target: String, hits: Int, snippets: List<String>): String {
        if (!matched) return "matched: false\ntarget: $target"
        val head = "matched: true\ntarget: $target\nhits: $hits"
        return if (snippets.isEmpty()) head else head + "\n" + snippets.joinToString("\n")
    }

    fun renderEndpoints(rows: List<EndpointRow>): String {
        if (rows.isEmpty()) return "(no endpoints)"
        val methodW = rows.maxOf { it.method.length }
        val hostW = minOf(48, rows.maxOf { it.host.length })
        val pathW = minOf(64, rows.maxOf { it.path.length })
        return rows.joinToString("\n") { r ->
            r.method.padEnd(methodW) +
                "  " + truncate(r.host, hostW).padEnd(hostW) +
                "  " + truncate(r.path, pathW).padEnd(pathW) +
                "  ×${r.count}"
        }
    }

    data class EndpointRow(val method: String, val host: String, val path: String, val count: Int)

    fun renderStats(
        total: Int,
        byMethod: Map<String, Int>,
        byClass: Map<String, Int>,
        byHost: List<Pair<String, Int>>,
    ): String {
        val lines = mutableListOf("total entries: $total")
        if (byMethod.isNotEmpty()) {
            lines += "by method: " + byMethod.toSortedMap().entries.joinToString(", ") { "${it.key}=${it.value}" }
        }
        if (byClass.isNotEmpty()) {
            lines += "by status: " + byClass.toSortedMap().entries.joinToString(", ") { "${it.key}=${it.value}" }
        }
        if (byHost.isNotEmpty()) {
            lines += "top hosts:"
            for ((host, n) in byHost) lines += "  $host  ×$n"
        }
        return lines.joinToString("\n")
    }

    fun errorLine(message: String): String = "error: $message"
}
