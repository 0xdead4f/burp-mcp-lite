package io.github.dead4f.burpmcplite.filters

import io.github.dead4f.burpmcplite.format.renderText
import io.github.dead4f.burpmcplite.snapshot.HistoryEntry

/**
 * Filter helpers — port of `src/filters.ts`. Status range parsing accepts
 * comma-separated lists of: exact codes (`404`), classes (`2xx`), or ranges
 * (`200-299`, `200-3xx`). Unknown tokens fail closed (never-match) so the
 * filter doesn't silently pass everything.
 */
object Filters {

    fun interface StatusPredicate { fun test(status: Int): Boolean }

    enum class MatchTarget(val key: String) {
        RequestBody("request.body"),
        RequestHeaders("request.headers"),
        RequestAll("request.all"),
        ResponseBody("response.body"),
        ResponseHeaders("response.headers"),
        ResponseAll("response.all");

        companion object {
            fun parse(spec: String): MatchTarget =
                entries.firstOrNull { it.key == spec.trim().lowercase() } ?: ResponseBody
        }
    }

    private val STATUS_TOKEN_RX = Regex("^([1-5])xx$")

    private fun expandLow(tok: String): Int {
        val t = tok.trim().lowercase()
        val m = STATUS_TOKEN_RX.matchEntire(t)
        if (m != null) return m.groupValues[1].toInt() * 100
        return t.toIntOrNull() ?: Int.MIN_VALUE
    }

    private fun expandHigh(tok: String): Int {
        val t = tok.trim().lowercase()
        val m = STATUS_TOKEN_RX.matchEntire(t)
        if (m != null) return m.groupValues[1].toInt() * 100 + 99
        return t.toIntOrNull() ?: Int.MIN_VALUE
    }

    fun parseStatusFilter(spec: String): StatusPredicate {
        val matchers = mutableListOf<StatusPredicate>()
        for (raw in spec.split(",")) {
            val token = raw.trim().lowercase()
            if (token.isEmpty()) continue

            if ("-" in token) {
                val parts = token.split("-", limit = 2)
                val lo = expandLow(parts[0])
                val hi = if (parts.size > 1) expandHigh(parts[1]) else Int.MIN_VALUE
                if (lo != Int.MIN_VALUE && hi != Int.MIN_VALUE) {
                    matchers += StatusPredicate { it in lo..hi }
                } else {
                    matchers += StatusPredicate { false }
                }
                continue
            }
            val cls = STATUS_TOKEN_RX.matchEntire(token)
            if (cls != null) {
                val c = cls.groupValues[1].toInt()
                matchers += StatusPredicate { it in (c * 100)..(c * 100 + 99) }
                continue
            }
            val exact = token.toIntOrNull()
            if (exact != null) {
                matchers += StatusPredicate { it == exact }
            } else {
                matchers += StatusPredicate { false }
            }
        }
        if (matchers.isEmpty()) return StatusPredicate { true }
        return StatusPredicate { s -> matchers.any { it.test(s) } }
    }

    private fun normalizeMethods(m: Any?): Set<String>? {
        if (m == null) return null
        val items = when (m) {
            is String -> m.split(",").map(String::trim).filter(String::isNotEmpty)
            is List<*> -> m.filterIsInstance<String>().map(String::trim).filter(String::isNotEmpty)
            else -> emptyList()
        }
        if (items.isEmpty()) return null
        return items.map { it.uppercase() }.toSet()
    }

    fun resolveMatchTarget(e: HistoryEntry, target: MatchTarget): String = when (target) {
        MatchTarget.ResponseBody -> e.response.body
        MatchTarget.ResponseHeaders -> e.response.headers.renderText()
        MatchTarget.ResponseAll -> e.response.headers.renderText() + "\n\n" + e.response.body
        MatchTarget.RequestBody -> e.request.body
        MatchTarget.RequestHeaders -> e.request.headers.renderText()
        MatchTarget.RequestAll -> e.request.headers.renderText() + "\n\n" + e.request.body
    }

    data class BuildArgs(
        val host: String? = null,
        val path: String? = null,
        val method: Any? = null, // String | List<String>
        val status: String? = null,
        val mime: String? = null,
        val match: String? = null,
        val matchIn: String = MatchTarget.ResponseBody.key,
        val caseSensitive: Boolean = false,
    )

    fun build(args: BuildArgs = BuildArgs()): (HistoryEntry) -> Boolean {
        val hostNeedle = args.host?.lowercase()
        val pathRx = args.path?.let {
            if (args.caseSensitive) Regex(it) else Regex(it, RegexOption.IGNORE_CASE)
        }
        val methods = normalizeMethods(args.method)
        val statusPred = args.status?.let { parseStatusFilter(it) }
        val mimeNeedle = args.mime?.lowercase()
        val matchRx = args.match?.let {
            if (args.caseSensitive) Regex(it) else Regex(it, RegexOption.IGNORE_CASE)
        }
        val target = MatchTarget.parse(args.matchIn)

        return body@ { e ->
            if (methods != null && e.request.method.uppercase() !in methods) return@body false
            if (hostNeedle != null && !e.request.host.lowercase().contains(hostNeedle)) return@body false
            if (pathRx != null && !pathRx.containsMatchIn(e.request.path)) return@body false
            if (statusPred != null && !statusPred.test(e.response.status)) return@body false
            if (mimeNeedle != null && !e.response.contentType.lowercase().contains(mimeNeedle)) return@body false
            if (matchRx != null) {
                val text = resolveMatchTarget(e, target)
                if (!matchRx.containsMatchIn(text)) return@body false
            }
            true
        }
    }
}
