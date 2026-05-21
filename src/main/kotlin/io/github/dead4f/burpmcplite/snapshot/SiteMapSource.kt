package io.github.dead4f.burpmcplite.snapshot

import burp.api.montoya.MontoyaApi
import burp.api.montoya.http.message.HttpRequestResponse
import burp.api.montoya.sitemap.SiteMapFilter
import io.github.dead4f.burpmcplite.format.HttpParse

/**
 * Realtime adapter onto Burp's site map.
 *
 * Distinct from [HistorySource], which reads `api.proxy().history()` — this
 * reads `api.siteMap().requestResponses(...)`, the cumulative inventory Burp
 * builds from proxy traffic *plus* spider / scanner discoveries. Most
 * usefully consumed deduplicated by method+host+path.
 *
 * ## About ids
 *
 * `HttpRequestResponse` from the site map does not carry a Burp UI id (only
 * `ProxyHttpRequestResponse` in proxy history does). So site map entries are
 * deliberately *id-less* — [SiteMapEntry] has only the parsed wire data.
 * Cross-reference with proxy history via `list_history` (host+path) if you
 * need a stable handle for `view_request` / `view_response`.
 *
 * Implementations:
 *   - [BurpSiteMapSource] — production, reads `api.siteMap()`.
 *   - [FakeSiteMapSource] — tests, holds raw HTTP wire strings.
 */
interface SiteMapSource {
    /**
     * Lazy iteration of site map entries. [prefix], when non-null/non-blank,
     * is passed through to Burp's native [SiteMapFilter.prefixFilter] for
     * an in-engine pre-filter (significantly faster on large site maps than
     * post-filtering host/path ourselves).
     */
    fun entries(prefix: String? = null): Sequence<SiteMapEntry>

    /** Total entries currently in the site map (no prefix filter). */
    fun size(): Int
}

/** Pure data — parsed request + (possibly empty) response from one site-map entry. */
data class SiteMapEntry(
    val request: ParsedRequest,
    val response: ParsedResponse,
)

/** Production [SiteMapSource]. */
class BurpSiteMapSource(private val api: MontoyaApi) : SiteMapSource {

    override fun entries(prefix: String?): Sequence<SiteMapEntry> {
        val list: List<HttpRequestResponse> = if (prefix.isNullOrBlank()) {
            api.siteMap().requestResponses()
        } else {
            api.siteMap().requestResponses(SiteMapFilter.prefixFilter(prefix))
        }
        return list.asSequence().map(::toEntry)
    }

    override fun size(): Int = api.siteMap().requestResponses().size

    private fun toEntry(p: HttpRequestResponse): SiteMapEntry {
        val reqBytes = runCatching { p.request()?.toString() }.getOrNull() ?: ""
        val respBytes = runCatching { p.response()?.toString() }.getOrNull() ?: ""
        return SiteMapEntry(
            request = HttpParse.parseRequest(reqBytes),
            response = HttpParse.parseResponse(respBytes),
        )
    }
}

/**
 * Test-only [SiteMapSource]. Holds [RawEntry] strings (shared with
 * [FakeHistorySource]) so tests can feed plain HTTP wire bytes.
 *
 * The prefix filter mimics Burp's by comparing against `https://host/path`
 * — sufficient for unit tests; production uses Burp's native filter.
 */
class FakeSiteMapSource(
    private val raws: MutableList<RawEntry> = mutableListOf(),
) : SiteMapSource {

    companion object {
        fun of(vararg entries: RawEntry): FakeSiteMapSource =
            FakeSiteMapSource(entries.toMutableList())
    }

    override fun entries(prefix: String?): Sequence<SiteMapEntry> {
        val all = raws.asSequence().map { r ->
            SiteMapEntry(
                request = HttpParse.parseRequest(r.request),
                response = HttpParse.parseResponse(r.response),
            )
        }
        if (prefix.isNullOrBlank()) return all
        return all.filter { e ->
            val url = "https://${e.request.host}${e.request.path}"
            url.startsWith(prefix)
        }
    }

    override fun size(): Int = raws.size

    fun add(r: RawEntry) { raws.add(r) }
    fun clear() { raws.clear() }
}
