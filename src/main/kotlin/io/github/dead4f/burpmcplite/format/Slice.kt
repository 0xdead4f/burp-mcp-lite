package io.github.dead4f.burpmcplite.format

/**
 * Body slicing — port of `src/format/slice.ts`. Supported `spec` forms
 * (case-insensitive prefix; the regex body is case-sensitive):
 *
 *   full          -> whole body
 *   none          -> empty
 *   head:N        -> first N lines
 *   tail:N        -> last N lines
 *   /regex/       -> matching lines plus `context` lines on either side
 *
 * `auto` is resolved upstream of this function.
 */
object Slice {
    /**
     * Per-line cap for emitted snippets. A "line" in proxy traffic can be a
     * 50KB minified-JSON blob — emitting it whole defeats the point of the
     * predicate viewer. We center the window on the match when there is one,
     * head-trim otherwise.
     */
    const val SNIPPET_LINE_CAP: Int = 240
    const val ELLIPSIS: String = "…"

    data class Result(
        val text: String,
        val truncated: Boolean,
        val hitCount: Int,
        val totalLines: Int,
    )

    fun windowedLine(line: String, rx: Regex?, cap: Int = SNIPPET_LINE_CAP): String {
        if (line.length <= cap) return line
        val m = rx?.find(line)
        if (m == null) {
            return line.substring(0, cap - 1) + ELLIPSIS
        }
        val start = m.range.first
        val end = m.range.last + 1
        val matchLen = end - start
        if (matchLen + 2 >= cap) {
            return ELLIPSIS + line.substring(start, start + cap - 2) + ELLIPSIS
        }
        val remaining = cap - matchLen - 2
        val pre = remaining / 2
        val post = remaining - pre
        val winStart = maxOf(0, start - pre)
        val winEnd = minOf(line.length, end + post)
        var out = line.substring(winStart, winEnd)
        if (winStart > 0) out = ELLIPSIS + out
        if (winEnd < line.length) out = out + ELLIPSIS
        return out
    }

    private fun splitLines(body: String): List<String> =
        if (body.isEmpty()) emptyList() else body.replace("\r\n", "\n").split("\n")

    fun sliceBody(body: String, spec: String, context: Int = 1): Result {
        if (body.isEmpty()) return Result("", false, 0, 0)

        val trimmed = spec.trim()
        val lines = splitLines(body)
        val total = lines.size
        val lower = trimmed.lowercase()

        if (lower == "full") return Result(body, false, 0, total)
        if (lower == "none") return Result("", true, 0, total)

        if (lower.startsWith("head:")) {
            val n = maxOf(0, trimmed.substring(5).toIntOrNull() ?: 0)
            val kept = lines.take(n)
            return Result(kept.joinToString("\n"), n < total, 0, total)
        }
        if (lower.startsWith("tail:")) {
            val n = maxOf(0, trimmed.substring(5).toIntOrNull() ?: 0)
            val kept = if (n == 0) emptyList() else lines.takeLast(n)
            return Result(kept.joinToString("\n"), n < total, 0, total)
        }

        if (trimmed.length >= 2 && trimmed.startsWith("/") && trimmed.endsWith("/")) {
            val pattern = trimmed.substring(1, trimmed.length - 1)
            val rx = try {
                Regex(pattern)
            } catch (e: Exception) {
                return Result(
                    "<invalid regex: \"${pattern.replace("\"", "\\\"")}\">",
                    true, 0, total,
                )
            }

            val keep = sortedSetOf<Int>()
            val hitLines = mutableSetOf<Int>()
            var hits = 0
            for (i in lines.indices) {
                if (rx.containsMatchIn(lines[i])) {
                    hits += 1
                    hitLines.add(i)
                    val lo = maxOf(0, i - context)
                    val hi = minOf(total, i + context + 1)
                    for (j in lo until hi) keep.add(j)
                }
            }
            if (keep.isEmpty()) return Result("<no matches>", true, 0, total)

            val out = StringBuilder()
            var prev: Int? = null
            var anyLineTruncated = false
            for (i in keep) {
                if (prev != null && i != prev + 1) {
                    if (out.isNotEmpty()) out.append("\n")
                    out.append("...")
                }
                val windowRx = if (hitLines.contains(i)) rx else null
                val rendered = windowedLine(lines[i], windowRx)
                if (rendered != lines[i]) anyLineTruncated = true
                if (out.isNotEmpty()) out.append("\n")
                out.append("[L").append(i + 1).append("] ").append(rendered)
                prev = i
            }
            return Result(
                out.toString(),
                anyLineTruncated || keep.size < total,
                hits,
                total,
            )
        }

        // Unknown spec — fall back to full so we don't surprise the model.
        return Result(body, false, 0, total)
    }
}
