package io.github.dead4f.burpmcplite.tools

import io.github.dead4f.burpmcplite.format.Render
import io.github.dead4f.burpmcplite.format.statusClass
import io.github.dead4f.burpmcplite.snapshot.HistorySource
import kotlinx.serialization.Serializable

@Serializable
class StatsArgs

object StatsTool {
    fun run(source: HistorySource, @Suppress("UNUSED_PARAMETER") args: StatsArgs = StatsArgs()): String {
        val byMethod = mutableMapOf<String, Int>()
        val byClass = mutableMapOf<String, Int>()
        val byHost = mutableMapOf<String, Int>()
        var total = 0
        for (e in source.entries()) {
            total += 1
            val m = e.request.method.ifEmpty { "-" }
            byMethod.merge(m, 1, Int::plus)
            byClass.merge(statusClass(e.response.status), 1, Int::plus)
            if (e.request.host.isNotEmpty()) byHost.merge(e.request.host, 1, Int::plus)
        }
        val topHosts = byHost.entries
            .sortedByDescending { it.value }
            .take(5)
            .map { it.key to it.value }
        return Render.renderStats(
            total = total,
            byMethod = byMethod,
            byClass = byClass,
            byHost = topHosts,
        )
    }
}
