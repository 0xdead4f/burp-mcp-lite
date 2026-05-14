package io.github.dead4f.burpmcplite.tools

import io.github.dead4f.burpmcplite.filters.Filters
import io.github.dead4f.burpmcplite.format.Render
import io.github.dead4f.burpmcplite.format.Slice
import io.github.dead4f.burpmcplite.snapshot.HistorySource
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class MatchArgs(
    val id: Int,
    val pattern: String,
    val target: String? = null,
    @SerialName("case_sensitive") val caseSensitive: Boolean? = null,
    val context: Int? = null,
    @SerialName("max_hits") val maxHits: Int? = null,
)

object MatchTool {
    fun run(source: HistorySource, args: MatchArgs): String {
        val target = Filters.MatchTarget.parse(args.target ?: Filters.MatchTarget.ResponseBody.key)
        val caseSensitive = args.caseSensitive ?: false
        val context = args.context ?: 0
        val maxHits = args.maxHits ?: 10

        val e = source.byId(args.id) ?: return Render.errorLine(
            "id ${args.id} not found in current Burp history (${source.size()} entries). " +
                "Run list_history to see current ids.",
        )
        val rx = try {
            if (caseSensitive) Regex(args.pattern) else Regex(args.pattern, RegexOption.IGNORE_CASE)
        } catch (e2: Exception) {
            return Render.errorLine("invalid regex \"${args.pattern}\": ${e2.message}")
        }

        val text = Filters.resolveMatchTarget(e, target)
        val lines = if (text.isEmpty()) emptyList() else text.replace("\r\n", "\n").split("\n")

        val hitIndices = mutableListOf<Int>()
        for (i in lines.indices) if (rx.containsMatchIn(lines[i])) hitIndices += i
        if (hitIndices.isEmpty()) {
            return Render.renderMatch(matched = false, target = target.key, hits = 0, snippets = emptyList())
        }
        val hitSet = hitIndices.toSet()
        val snippets = mutableListOf<String>()
        val rendered = sortedSetOf<Int>()
        for (i in hitIndices.take(maxHits)) {
            val lo = maxOf(0, i - context)
            val hi = minOf(lines.size, i + context + 1)
            if (snippets.isNotEmpty() && rendered.isNotEmpty()) {
                val maxRendered = rendered.last()
                if (lo > maxRendered + 1) snippets += "..."
            }
            for (j in lo until hi) {
                if (j in rendered) continue
                rendered += j
                val windowRx = if (j in hitSet) rx else null
                snippets += "[L${j + 1}] ${Slice.windowedLine(lines[j], windowRx)}"
            }
        }
        return Render.renderMatch(
            matched = true, target = target.key, hits = hitIndices.size, snippets = snippets,
        )
    }
}
