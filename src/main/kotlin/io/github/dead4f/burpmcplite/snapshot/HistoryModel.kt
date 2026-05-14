package io.github.dead4f.burpmcplite.snapshot

import io.github.dead4f.burpmcplite.format.Header
import io.github.dead4f.burpmcplite.format.findHeader
import java.time.Instant
import java.time.format.DateTimeFormatter

/**
 * Pure data model for a single history entry — Montoya-independent so the
 * tools and tests run on plain Kotlin types. [HistorySource] is the only
 * file that knows about Burp's API; it builds these from Montoya objects.
 */
data class ParsedRequest(
    val method: String,
    val target: String,
    val host: String,
    val path: String,
    val httpVersion: String,
    val headers: List<Header>,
    val body: String,
)

data class ParsedResponse(
    val httpVersion: String,
    val status: Int,
    val reason: String,
    val contentType: String,
    val contentLength: Int,
    val headers: List<Header>,
    val body: String,
)

data class HistoryEntry(
    val id: Int,
    /** Timestamp Burp recorded the entry, or [Instant.EPOCH] when unknown. */
    val recordedAt: Instant,
    val notes: String?,
    val request: ParsedRequest,
    val response: ParsedResponse,
)

/**
 * Prefer the response `Date` header (per-entry, server clock); fall back
 * to [HistoryEntry.recordedAt] (Burp's own capture timestamp via
 * `ProxyHttpRequestResponse.time()`).
 */
fun entryTimestamp(e: HistoryEntry): Instant {
    val dateHdr = e.response.headers.findHeader("Date")
    if (dateHdr != null) {
        try {
            return Instant.from(DateTimeFormatter.RFC_1123_DATE_TIME.parse(dateHdr))
        } catch (_: Exception) {
            // fall through
        }
    }
    return e.recordedAt
}
