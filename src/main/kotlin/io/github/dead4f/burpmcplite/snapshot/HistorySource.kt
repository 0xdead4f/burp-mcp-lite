package io.github.dead4f.burpmcplite.snapshot

import burp.api.montoya.MontoyaApi
import burp.api.montoya.proxy.ProxyHttpRequestResponse
import io.github.dead4f.burpmcplite.format.HttpParse
import java.time.Instant

/**
 * Realtime adapter onto Burp's proxy history.
 *
 * Every tool call goes through this and lands on `api.proxy().history()`
 * fresh — no caching, no TTL, no anchor probe. "What Burp sees right now
 * is what the tool sees."
 *
 * ## About the `id`
 *
 * Montoya exposes `int ProxyHttpRequestResponse.id()` — that's Burp's
 * native proxy-history number, the same `#` you see in the UI table.
 * We pass it straight through. So:
 *
 *   - ids match Burp's UI column exactly (e.g. `40479`)
 *   - eviction doesn't shift ids: when entry id=8000 is dropped from the
 *     list, entry id=40478 keeps id=40478
 *   - `byId` does a linear scan over the current list (O(N)) — typically
 *     microseconds for a few-thousand-entry history
 *
 * Implementations:
 *   - [BurpHistorySource]   — production, reads `api.proxy().history()`.
 *   - [FakeHistorySource]   — tests, holds an in-memory list with explicit
 *     ids so eviction can be simulated.
 */
interface HistorySource {
    /** Lazy iteration in capture order. Each element is parsed on demand. */
    fun entries(): Sequence<HistoryEntry>

    /** Parsed entry with this Burp id, or null if no such id is in the current history. */
    fun byId(id: Int): HistoryEntry?

    /** Current number of entries in Burp's proxy history. */
    fun size(): Int
}

/** Production [HistorySource]. */
class BurpHistorySource(private val api: MontoyaApi) : HistorySource {

    override fun entries(): Sequence<HistoryEntry> {
        // Snapshot the list reference once per call so iteration is consistent
        // even if Burp appends new entries mid-iteration. The list itself is
        // a Montoya-managed view; position N is stable within this call.
        val list = api.proxy().history()
        return list.asSequence().map { toEntry(it) }
    }

    override fun byId(id: Int): HistoryEntry? {
        val list = api.proxy().history()
        val hit = list.firstOrNull { runCatching { it.id() }.getOrNull() == id } ?: return null
        return toEntry(hit)
    }

    override fun size(): Int = api.proxy().history().size

    private fun toEntry(p: ProxyHttpRequestResponse): HistoryEntry {
        val reqBytes = p.request()?.toString() ?: ""
        val respBytes = p.response()?.toString() ?: ""
        val recordedAt: Instant = runCatching { p.time()?.toInstant() }.getOrNull() ?: Instant.EPOCH
        val notes = runCatching { p.annotations().notes() }.getOrNull()
        val id = runCatching { p.id() }.getOrDefault(-1)
        return HistoryEntry(
            id = id,
            recordedAt = recordedAt,
            notes = notes,
            request = HttpParse.parseRequest(reqBytes),
            response = HttpParse.parseResponse(respBytes),
        )
    }
}

/**
 * Test-only [HistorySource] holding raw wire bytes plus an explicit id per
 * entry — so tests can simulate eviction by leaving gaps in the id space.
 *
 * If you don't care about ids, use [FakeHistorySource.of] which auto-numbers
 * from `0`.
 */
data class RawEntry(val request: String, val response: String, val notes: String? = null)

class FakeHistorySource(
    private val raws: MutableList<Pair<Int, RawEntry>> = mutableListOf(),
) : HistorySource {

    companion object {
        /** Convenience: build a source whose ids are 0, 1, 2 … */
        fun of(vararg entries: RawEntry): FakeHistorySource =
            FakeHistorySource(entries.mapIndexed { i, r -> i to r }.toMutableList())
    }

    override fun entries(): Sequence<HistoryEntry> =
        raws.asSequence().map { (id, r) -> build(id, r) }

    override fun byId(id: Int): HistoryEntry? {
        val (_, r) = raws.firstOrNull { it.first == id } ?: return null
        return build(id, r)
    }

    override fun size(): Int = raws.size

    fun add(id: Int, r: RawEntry) { raws.add(id to r) }
    fun clear() { raws.clear() }

    private fun build(id: Int, r: RawEntry): HistoryEntry = HistoryEntry(
        id = id,
        recordedAt = Instant.EPOCH,
        notes = r.notes,
        request = HttpParse.parseRequest(r.request),
        response = HttpParse.parseResponse(r.response),
    )
}
