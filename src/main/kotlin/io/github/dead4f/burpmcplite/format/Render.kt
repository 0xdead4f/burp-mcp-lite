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

    fun formatEntryTime(e: HistoryEntry): String = formatInstant(entryTimestamp(e))

    /** `HH:mm:ss` for today, `MM-DD HH:mm:ss` otherwise. */
    fun formatInstant(instant: java.time.Instant): String {
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

    /** One host inventory row in `sitemap mode=domains` output. */
    data class DomainRow(val host: String, val count: Int)

    fun renderDomains(rows: List<DomainRow>, total: Int, offset: Int): String {
        if (rows.isEmpty()) return "(no domains)\n-- 0 of $total (offset $offset) --"
        val hostW = minOf(64, rows.maxOf { it.host.length })
        val lines = rows.map { r ->
            truncate(r.host, hostW).padEnd(hostW) + "  ×${r.count}"
        }.toMutableList()
        lines += "-- ${rows.size} of $total (offset $offset) --"
        return lines.joinToString("\n")
    }

    fun renderDomainsNdjson(rows: List<DomainRow>): String =
        rows.joinToString("\n") { r ->
            JsonObject(
                mapOf("host" to JsonPrimitive(r.host), "count" to JsonPrimitive(r.count)),
            ).toString()
        }

    /**
     * One deduplicated site-map row, scoped to a single domain (host is
     * implicit). `status` / `mime` are the *last seen* values; `count` is
     * the hit total.
     */
    data class SitemapRow(
        val method: String,
        val path: String,
        val status: Int,
        val mime: String,
        val count: Int,
    )

    fun renderSitemap(domain: String, rows: List<SitemapRow>, total: Int, offset: Int): String {
        val header = "domain: $domain"
        if (rows.isEmpty()) return "$header\n(no entries)\n-- 0 of $total (offset $offset) --"
        val methodW = rows.maxOf { it.method.length }
        val statusW = maxOf(3, rows.maxOf { (if (it.status > 0) it.status.toString() else "-").length })
        val pathW = minOf(72, rows.maxOf { it.path.length })
        val mimeW = minOf(24, rows.maxOf { it.mime.ifEmpty { "-" }.length })
        val lines = mutableListOf(header)
        for (r in rows) {
            val statusCell = if (r.status > 0) r.status.toString() else "-"
            val mimeCell = r.mime.ifEmpty { "-" }
            lines += r.method.padEnd(methodW) +
                "  " + statusCell.padEnd(statusW) +
                "  " + truncate(r.path, pathW).padEnd(pathW) +
                "  " + truncate(mimeCell, mimeW).padEnd(mimeW) +
                "  ×${r.count}"
        }
        lines += "-- ${rows.size} of $total (offset $offset) --"
        return lines.joinToString("\n")
    }

    fun renderSitemapNdjson(rows: List<SitemapRow>): String =
        rows.joinToString("\n") { r ->
            JsonObject(
                mapOf(
                    "method" to JsonPrimitive(r.method),
                    "status" to JsonPrimitive(r.status),
                    "path" to JsonPrimitive(r.path),
                    "mime" to JsonPrimitive(r.mime),
                    "count" to JsonPrimitive(r.count),
                ),
            ).toString()
        }

    /** One flat (non-dedup) site-map row — method/status/path only, domain-scoped. */
    data class SitemapFlatRow(val method: String, val status: Int, val path: String)

    fun renderSitemapFlat(domain: String, rows: List<SitemapFlatRow>, total: Int, offset: Int): String {
        val header = "domain: $domain"
        if (rows.isEmpty()) return "$header\n(no entries)\n-- 0 of $total (offset $offset) --"
        val methodW = rows.maxOf { it.method.length }
        val statusW = maxOf(3, rows.maxOf { (if (it.status > 0) it.status.toString() else "-").length })
        val pathW = minOf(96, rows.maxOf { it.path.length })
        val lines = mutableListOf(header)
        for (r in rows) {
            val statusCell = if (r.status > 0) r.status.toString() else "-"
            lines += r.method.padEnd(methodW) +
                "  " + statusCell.padEnd(statusW) +
                "  " + truncate(r.path, pathW).padEnd(pathW)
        }
        lines += "-- ${rows.size} of $total (offset $offset) --"
        return lines.joinToString("\n")
    }

    fun renderSitemapFlatNdjson(rows: List<SitemapFlatRow>): String =
        rows.joinToString("\n") { r ->
            JsonObject(
                mapOf(
                    "method" to JsonPrimitive(r.method),
                    "status" to JsonPrimitive(r.status),
                    "path" to JsonPrimitive(r.path),
                ),
            ).toString()
        }

    // ---- collaborator ---------------------------------------------------

    /** One minted payload, as rendered by `collaborator_payload`. */
    data class PayloadRow(val id: String, val value: String, val customData: String?)

    fun renderPayloads(server: String, rows: List<PayloadRow>): String {
        if (rows.isEmpty()) return "(no payloads generated)"
        if (rows.size == 1) {
            val r = rows.single()
            val lines = mutableListOf("payload: ${r.value}", "id: ${r.id}")
            if (r.customData != null) lines += "custom_data: ${r.customData}"
            return lines.joinToString("\n")
        }
        val showData = rows.any { it.customData != null }
        val idW = maxOf("id".length, rows.maxOf { it.id.length })
        val valW = maxOf("payload".length, rows.maxOf { it.value.length })
        val lines = mutableListOf("server: $server")
        lines += buildString {
            append("id".padEnd(idW)).append("  ").append("payload".padEnd(valW))
            if (showData) append("  ").append("custom_data")
        }.trimEnd()
        for (r in rows) {
            lines += buildString {
                append(r.id.padEnd(idW)).append("  ").append(r.value.padEnd(valW))
                if (showData) append("  ").append(r.customData ?: "-")
            }.trimEnd()
        }
        lines += "-- ${rows.size} payloads --"
        return lines.joinToString("\n")
    }

    /**
     * One out-of-band hit in the compact `collaborator_log` table. [type] is
     * pre-composed (e.g. `DNS(A)`); [detail] is rendered as a separate block
     * below the table and only when the caller asked for it.
     */
    data class InteractionRow(
        val time: String,
        val type: String,
        val client: String,
        val payload: String,
        val customData: String?,
        val detail: String?,
    )

    fun renderInteractions(
        rows: List<InteractionRow>,
        total: Int,
        offset: Int,
        issued: Int,
        note: String? = null,
    ): String {
        if (rows.isEmpty()) {
            val head = if (issued > 0) "(no interactions) — $issued payload(s) issued this session"
            else "(no interactions)"
            return "$head\n-- 0 of $total (offset $offset) --"
        }
        val showData = rows.any { it.customData != null }
        val cols = mutableListOf("time", "type", "client", "payload")
        if (showData) cols += "data"
        val values: List<List<String>> = rows.map { r ->
            val base = mutableListOf(r.time, r.type, r.client.ifEmpty { "-" }, r.payload)
            if (showData) base += (r.customData ?: "-")
            base
        }
        val widths = cols.indices.map { i ->
            maxOf(cols[i].length, values.maxOf { it[i].length })
        }
        val lines = mutableListOf(
            cols.mapIndexed { i, c -> c.padEnd(widths[i]) }.joinToString("  ").trimEnd(),
        )
        for (v in values) {
            lines += v.mapIndexed { i, c -> c.padEnd(widths[i]) }.joinToString("  ").trimEnd()
        }
        lines += "-- ${rows.size} of $total (offset $offset) --"

        val withDetail = rows.filter { !it.detail.isNullOrEmpty() }
        if (withDetail.isNotEmpty()) {
            for (r in withDetail) {
                lines += ""
                lines += "[${r.payload} ${r.type} ${r.time}]"
                lines += r.detail!!
            }
        }
        if (note != null) lines += note
        return lines.joinToString("\n")
    }

    fun renderInteractionsNdjson(rows: List<InteractionRow>, includeDetail: Boolean): String =
        rows.joinToString("\n") { r ->
            val map = buildMap<String, JsonElement> {
                put("time", JsonPrimitive(r.time))
                put("type", JsonPrimitive(r.type))
                put("client", JsonPrimitive(r.client))
                put("payload", JsonPrimitive(r.payload))
                put("custom_data", r.customData?.let { JsonPrimitive(it) } ?: JsonNull)
                if (includeDetail) put("detail", r.detail?.let { JsonPrimitive(it) } ?: JsonNull)
            }
            JsonObject(map).toString()
        }

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
