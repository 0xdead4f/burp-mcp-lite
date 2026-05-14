package io.github.dead4f.burpmcplite.format

/**
 * Header-value redaction. Default-redact a small set of auth/secret-bearing
 * header values to `<redacted Nc>` so the model can still see "auth is
 * present" without burning tokens on the bytes.
 */
object Redact {
    val DEFAULT_REDACT_NAMES: Set<String> = setOf(
        "authorization",
        "proxy-authorization",
        "cookie",
        "set-cookie",
        "x-api-key",
        "x-auth-token",
        "x-csrf-token",
        "x-access-token",
    )

    fun redactValue(name: String, value: String): String {
        if (name.lowercase() !in DEFAULT_REDACT_NAMES) return value
        if (value.isEmpty()) return "<redacted>"
        return "<redacted ${value.length}c>"
    }

    fun apply(headers: List<Header>, redact: Boolean): List<Header> =
        if (!redact) headers else headers.map { (k, v) -> k to redactValue(k, v) }
}
