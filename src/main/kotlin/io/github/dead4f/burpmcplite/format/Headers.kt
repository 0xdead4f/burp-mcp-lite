package io.github.dead4f.burpmcplite.format

/**
 * A single HTTP header as a (name, value) pair. Order-preserving lists of
 * headers are the canonical representation everywhere in this codebase —
 * we never collapse to a Map because duplicate names (Set-Cookie, Via, ...)
 * are real, and order matters for cookie parsing.
 */
typealias Header = Pair<String, String>

fun List<Header>.findHeader(name: String): String? {
    val lname = name.lowercase()
    for ((k, v) in this) if (k.lowercase() == lname) return v
    return null
}

fun List<Header>.renderText(): String =
    joinToString("\n") { (k, v) -> "$k: $v" }

fun statusClass(status: Int): String =
    if (status <= 0) "0xx" else "${status / 100}xx"
