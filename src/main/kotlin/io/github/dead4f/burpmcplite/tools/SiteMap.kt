package io.github.dead4f.burpmcplite.tools

import io.github.dead4f.burpmcplite.filters.Filters
import io.github.dead4f.burpmcplite.format.Render
import io.github.dead4f.burpmcplite.snapshot.HistoryEntry
import io.github.dead4f.burpmcplite.snapshot.SiteMapEntry
import io.github.dead4f.burpmcplite.snapshot.SiteMapSource
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonPrimitive
import java.time.Instant

@Serializable
data class SiteMapArgs(
    /** "domains" (default — host inventory) or "entries" (per-host endpoints). */
    val mode: String? = null,
    /** Host name (e.g. "api.example.com"). Required for mode=entries. */
    val domain: String? = null,
    /** Dedup by method+path within the domain. Default true. Entries only. */
    val dedup: Boolean? = null,
    val path: String? = null,
    val method: JsonElement? = null,
    val status: String? = null,
    val mime: String? = null,
    val match: String? = null,
    @SerialName("match_in") val matchIn: String? = null,
    val limit: Int? = null,
    val offset: Int? = null,
    val format: String? = null,
    /** Advanced: raw URL prefix for Burp's native filter. Overrides `domain`. */
    val prefix: String? = null,
)

/**
 * Two-mode view of Burp's site map (`api.siteMap()`).
 *
 *   - `mode="domains"` (default) — list of unique hosts, no path tree.
 *   - `mode="entries"` — endpoints under one `domain` (required). `dedup=true`
 *     (default) groups by method+path; `dedup=false` lists every entry as
 *     method/status/path.
 *
 * Host column is omitted in entries mode — the domain is implicit from the
 * scope arg, so repeating it on every row would waste tokens.
 */
object SiteMapTool {

    fun run(source: SiteMapSource, args: SiteMapArgs): String {
        return when ((args.mode ?: "domains").lowercase()) {
            "domains" -> runDomains(source, args)
            "entries" -> runEntries(source, args)
            else -> "error: mode must be \"domains\" or \"entries\""
        }
    }

    // ---- domains mode --------------------------------------------------

    private fun runDomains(source: SiteMapSource, args: SiteMapArgs): String {
        val limit = args.limit ?: 50
        val offset = args.offset ?: 0
        val format = (args.format ?: "text").lowercase()

        val counts = mutableMapOf<String, Int>()
        for (e in source.entries(null)) {
            val host = e.request.host
            if (host.isEmpty()) continue
            counts.merge(host, 1, Int::plus)
        }
        val all = counts.entries
            .map { (h, c) -> Render.DomainRow(h, c) }
            .sortedWith(compareByDescending<Render.DomainRow> { it.count }.thenBy { it.host })
        val total = all.size
        val page = all.drop(offset).take(maxOf(0, limit))

        return if (format == "json") Render.renderDomainsNdjson(page)
        else Render.renderDomains(page, total, offset)
    }

    // ---- entries mode --------------------------------------------------

    private fun runEntries(source: SiteMapSource, args: SiteMapArgs): String {
        val prefix = resolvePrefix(args)
            ?: return "error: mode=entries requires `domain` (or `prefix`)"
        val limit = args.limit ?: 50
        val offset = args.offset ?: 0
        val format = (args.format ?: "text").lowercase()
        val dedup = args.dedup ?: true
        val domainLabel = args.domain ?: prefix

        val methodArg: Any? = args.method?.let { el ->
            when (el) {
                is JsonPrimitive -> el.content
                else -> runCatching { el.jsonArray.map { it.jsonPrimitive.content } }.getOrNull()
            }
        }
        val filter = Filters.build(
            Filters.BuildArgs(
                path = args.path,
                method = methodArg,
                status = args.status,
                mime = args.mime,
                match = args.match,
                matchIn = args.matchIn ?: Filters.MatchTarget.ResponseBody.key,
            )
        )

        return if (dedup) {
            renderDedup(source, prefix, domainLabel, filter, limit, offset, format)
        } else {
            renderFlat(source, prefix, domainLabel, filter, limit, offset, format)
        }
    }

    private fun renderDedup(
        source: SiteMapSource,
        prefix: String,
        domainLabel: String,
        filter: (HistoryEntry) -> Boolean,
        limit: Int,
        offset: Int,
        format: String,
    ): String {
        val counts = mutableMapOf<String, Int>()
        val rowProto = mutableMapOf<String, Render.SitemapRow>()
        for (e in source.entries(prefix)) {
            if (!filter(asFilterable(e))) continue
            val pathNoQuery = e.request.path.substringBefore('?')
            val key = "${e.request.method}|$pathNoQuery"
            counts.merge(key, 1, Int::plus)
            rowProto[key] = Render.SitemapRow(
                method = e.request.method,
                path = pathNoQuery,
                status = e.response.status,
                mime = e.response.contentType,
                count = 0,
            )
        }
        val all = counts.entries
            .map { (k, c) -> rowProto.getValue(k).copy(count = c) }
            .sortedWith(
                compareByDescending<Render.SitemapRow> { it.count }
                    .thenBy { it.path },
            )
        val total = all.size
        val page = all.drop(offset).take(maxOf(0, limit))
        return if (format == "json") Render.renderSitemapNdjson(page)
        else Render.renderSitemap(domainLabel, page, total, offset)
    }

    private fun renderFlat(
        source: SiteMapSource,
        prefix: String,
        domainLabel: String,
        filter: (HistoryEntry) -> Boolean,
        limit: Int,
        offset: Int,
        format: String,
    ): String {
        // Materialize matched rows in iteration order. We stop early once
        // we have enough to satisfy offset+limit so big site maps don't
        // OOM us when the user pages.
        val want = offset + maxOf(0, limit)
        val collected = mutableListOf<Render.SitemapFlatRow>()
        var total = 0
        for (e in source.entries(prefix)) {
            if (!filter(asFilterable(e))) continue
            total += 1
            if (collected.size < want) {
                collected += Render.SitemapFlatRow(
                    method = e.request.method,
                    status = e.response.status,
                    path = e.request.path,
                )
            }
        }
        val page = collected.drop(offset).take(maxOf(0, limit))
        return if (format == "json") Render.renderSitemapFlatNdjson(page)
        else Render.renderSitemapFlat(domainLabel, page, total, offset)
    }

    // ---- helpers --------------------------------------------------------

    /**
     * Picks the URL prefix to pass through to Burp's native `SiteMapFilter`.
     * Returns `null` when the caller supplied neither `prefix` nor `domain`.
     * `prefix` wins if both are set.
     */
    private fun resolvePrefix(args: SiteMapArgs): String? {
        val raw = args.prefix?.trim().orEmpty()
        if (raw.isNotEmpty()) return raw
        val domain = args.domain?.trim().orEmpty()
        if (domain.isEmpty()) return null
        return if (domain.startsWith("http://") || domain.startsWith("https://")) domain
        else "https://$domain"
    }

    /** Reuse [Filters.build] by wrapping the parsed protocol pair as a [HistoryEntry]. */
    private fun asFilterable(e: SiteMapEntry): HistoryEntry = HistoryEntry(
        id = -1,
        recordedAt = Instant.EPOCH,
        notes = null,
        request = e.request,
        response = e.response,
    )
}
