package io.github.dead4f.burpmcplite.format

import io.github.dead4f.burpmcplite.snapshot.ParsedRequest
import io.github.dead4f.burpmcplite.snapshot.ParsedResponse

/**
 * Parses HTTP wire bytes (the strings Burp emits via Montoya's `toString()`).
 * Lenient: lines may be CRLF or LF; header values may contain colons (split
 * on the first only); body starts after the first blank line. Malformed
 * header lines without `:` become `(line, "")` rather than getting dropped.
 *
 * This is intentionally a separate, Montoya-independent parser so tests run
 * on pure strings. See [io.github.dead4f.burpmcplite.snapshot.BurpHistorySource]
 * for the Burp-side adapter that calls this on each `api.proxy().history()`.
 */
object HttpParse {

    private fun splitHeadBody(raw: String): Pair<String, String> {
        for (sep in arrayOf("\r\n\r\n", "\n\n")) {
            val idx = raw.indexOf(sep)
            if (idx != -1) return raw.substring(0, idx) to raw.substring(idx + sep.length)
        }
        return raw to ""
    }

    private fun splitLines(head: String): List<String> =
        head.replace("\r\n", "\n").split("\n")

    private fun parseHeaders(lines: List<String>): List<Header> {
        val out = mutableListOf<Header>()
        for (line in lines) {
            if (line.isEmpty()) continue
            val idx = line.indexOf(":")
            if (idx == -1) {
                out += line.trim() to ""
                continue
            }
            val name = line.substring(0, idx).trim()
            val value = line.substring(idx + 1).trimStart(' ', '\t')
            out += name to value
        }
        return out
    }

    private fun requestPath(target: String): String {
        if (target.startsWith("http://") || target.startsWith("https://")) {
            val noScheme = target.substringAfter("://", "")
            val slash = noScheme.indexOf('/')
            return if (slash != -1) noScheme.substring(slash) else "/"
        }
        return target
    }

    private fun responseContentType(headers: List<Header>): String {
        val ct = headers.findHeader("Content-Type") ?: return ""
        return ct.substringBefore(';').trim().lowercase()
    }

    private fun responseContentLength(body: String, headers: List<Header>): Int {
        if (body.isNotEmpty()) return body.toByteArray(Charsets.ISO_8859_1).size
        val cl = headers.findHeader("Content-Length") ?: return 0
        val n = cl.trim().toIntOrNull() ?: return 0
        return if (n >= 0) n else 0
    }

    fun parseRequest(raw: String): ParsedRequest {
        val (head, body) = splitHeadBody(raw)
        val lines = splitLines(head)
        if (lines.isEmpty() || lines[0].isEmpty()) {
            return ParsedRequest("", "", "", "", "", emptyList(), body)
        }
        val parts = lines[0].split(" ", limit = 3)
        val method = parts.getOrElse(0) { "" }
        val target = parts.getOrElse(1) { "" }
        val version = parts.getOrElse(2) { "" }
        val headers = parseHeaders(lines.drop(1))
        return ParsedRequest(
            method = method,
            target = target,
            host = headers.findHeader("Host") ?: "",
            path = requestPath(target),
            httpVersion = version,
            headers = headers,
            body = body,
        )
    }

    fun parseResponse(raw: String): ParsedResponse {
        val (head, body) = splitHeadBody(raw)
        val lines = splitLines(head)
        if (lines.isEmpty() || lines[0].isEmpty()) {
            return ParsedResponse("", 0, "", "", 0, emptyList(), body)
        }
        // First line: "HTTP/1.1 200 OK"; reason may contain spaces.
        val first = lines[0]
        val sp1 = first.indexOf(' ')
        val sp2 = if (sp1 == -1) -1 else first.indexOf(' ', sp1 + 1)
        val version = if (sp1 == -1) "" else first.substring(0, sp1)
        val statusStr = if (sp1 == -1) "" else first.substring(sp1 + 1, if (sp2 == -1) first.length else sp2)
        val reason = if (sp2 == -1) "" else first.substring(sp2 + 1)
        val status = statusStr.toIntOrNull() ?: 0
        val headers = parseHeaders(lines.drop(1))
        return ParsedResponse(
            httpVersion = version,
            status = status,
            reason = reason,
            contentType = responseContentType(headers),
            contentLength = responseContentLength(body, headers),
            headers = headers,
            body = body,
        )
    }
}
