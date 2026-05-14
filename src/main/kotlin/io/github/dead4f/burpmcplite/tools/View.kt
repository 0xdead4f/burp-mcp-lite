package io.github.dead4f.burpmcplite.tools

import io.github.dead4f.burpmcplite.format.Header
import io.github.dead4f.burpmcplite.format.Redact
import io.github.dead4f.burpmcplite.format.Render
import io.github.dead4f.burpmcplite.format.Slice
import io.github.dead4f.burpmcplite.snapshot.HistoryEntry
import io.github.dead4f.burpmcplite.snapshot.HistorySource
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class ViewRequestArgs(
    val id: Int,
    @SerialName("include_headers") val includeHeaders: Boolean? = null,
    @SerialName("include_cookies") val includeCookies: Boolean? = null,
    val redact: Boolean? = null,
    val body: String? = null,
    val context: Int? = null,
)

@Serializable
data class ViewResponseArgs(
    val id: Int,
    @SerialName("include_headers") val includeHeaders: Boolean? = null,
    @SerialName("include_set_cookie") val includeSetCookie: Boolean? = null,
    val redact: Boolean? = null,
    val body: String? = null,
    val context: Int? = null,
)

object ViewTool {
    private const val AUTO_FULL_THRESHOLD = 4 * 1024

    private fun filterHeaders(headers: List<Header>, includeCookies: Boolean, cookieName: String): List<Header> {
        if (includeCookies) return headers
        val lname = cookieName.lowercase()
        return headers.filter { it.first.lowercase() != lname }
    }

    private fun resolveBodySpec(spec: String, bodySize: Int): String {
        if (spec.lowercase() == "auto") {
            return if (bodySize <= AUTO_FULL_THRESHOLD) "full" else "head:20"
        }
        return spec
    }

    private fun notFound(source: HistorySource, id: Int): String =
        Render.errorLine(
            "id $id not found in current Burp history (${source.size()} entries). " +
                "Run list_history to see current ids — Burp may have evicted the entry under retention.",
        )

    fun runRequest(source: HistorySource, args: ViewRequestArgs): String {
        val e: HistoryEntry = source.byId(args.id) ?: return notFound(source, args.id)

        val includeHeaders = args.includeHeaders ?: false
        val includeCookies = args.includeCookies ?: false
        val redact = args.redact ?: true
        val body = args.body ?: "full"
        val context = args.context ?: 1

        val chosen: List<Header>
        val showHeaders: Boolean
        if (!includeHeaders && includeCookies) {
            chosen = e.request.headers.filter { it.first.lowercase() == "cookie" }
            showHeaders = chosen.isNotEmpty()
        } else {
            chosen = filterHeaders(e.request.headers, includeCookies, "Cookie")
            showHeaders = includeHeaders
        }
        val redacted = Redact.apply(chosen, redact)

        val spec = resolveBodySpec(body, e.request.body.length)
        val sliced = Slice.sliceBody(e.request.body, spec, context)
        val note: String? = if (sliced.truncated && (spec.startsWith("head:") || spec.startsWith("tail:")))
            "... (${sliced.totalLines} lines total; truncated)" else null

        return Render.renderRequestView(e, redacted, sliced.text, showHeaders, note)
    }

    fun runResponse(source: HistorySource, args: ViewResponseArgs): String {
        val e: HistoryEntry = source.byId(args.id) ?: return notFound(source, args.id)

        val includeHeaders = args.includeHeaders ?: false
        val includeSetCookie = args.includeSetCookie ?: false
        val redact = args.redact ?: true
        val body = args.body ?: "auto"
        val context = args.context ?: 1

        val chosen: List<Header>
        val showHeaders: Boolean
        if (!includeHeaders && includeSetCookie) {
            chosen = e.response.headers.filter { it.first.lowercase() == "set-cookie" }
            showHeaders = chosen.isNotEmpty()
        } else {
            chosen = filterHeaders(e.response.headers, includeSetCookie, "Set-Cookie")
            showHeaders = includeHeaders
        }
        val redacted = Redact.apply(chosen, redact)

        val spec = resolveBodySpec(body, e.response.body.length)
        val sliced = Slice.sliceBody(e.response.body, spec, context)
        val note: String? = when {
            body == "auto" && spec != "full" ->
                "... (auto-truncated; body is ${e.response.body.length} bytes — pass body=\"full\" to override)"
            sliced.truncated && (spec.startsWith("head:") || spec.startsWith("tail:")) ->
                "... (${sliced.totalLines} lines total; truncated)"
            else -> null
        }
        return Render.renderResponseView(e, redacted, sliced.text, showHeaders, note)
    }
}
