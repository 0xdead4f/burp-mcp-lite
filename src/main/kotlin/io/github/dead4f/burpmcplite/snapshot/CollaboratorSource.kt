package io.github.dead4f.burpmcplite.snapshot

import burp.api.montoya.MontoyaApi
import burp.api.montoya.collaborator.CollaboratorClient
import burp.api.montoya.collaborator.Interaction
import burp.api.montoya.collaborator.PayloadOption
import burp.api.montoya.collaborator.SecretKey
import java.time.Instant

/**
 * Adapter onto Burp Collaborator — payload minting plus an accumulating
 * interaction log.
 *
 * ## Why the log accumulates
 *
 * `CollaboratorClient.getAllInteractions()` is a *poll*: Burp hands back the
 * interactions it has picked up and does not guarantee it will hand the same
 * ones back on the next call. An agent that polls twice must not lose the hit
 * it saw on the first poll, so every poll is merged into a local append-only
 * log keyed by (payload id, type, timestamp, client). That key is correct
 * whether Burp drains on read or replays the full list — a replayed
 * interaction collapses onto the record already held.
 *
 * We always poll unfiltered and filter in-process. Pushing the filter into
 * `getInteractions(InteractionFilter)` would risk draining interactions that
 * don't match the filter and never seeing them again.
 *
 * ## Why the secret key is persisted
 *
 * Each [burp.api.montoya.collaborator.Collaborator.createClient] mints a new
 * client with its own payload namespace — payloads issued by an earlier
 * client are invisible to a later one. Since a fresh source is built every
 * time the MCP server starts, toggling the server off and on would otherwise
 * orphan every payload already planted in a target. Persisting the key and
 * going through `restoreClient` keeps one client (and its interaction
 * history) across restarts.
 *
 * Implementations:
 *   - [BurpCollaboratorSource] — production, talks to Burp.
 *   - [FakeCollaboratorSource] — tests, in-memory.
 */
interface CollaboratorSource {
    /** Collaborator server address, e.g. `oastify.com`. */
    fun server(): String

    /**
     * Mint [count] payloads. [customData] (alphanumeric, echoed back on every
     * resulting interaction) is suffixed with the payload index when [count]
     * > 1 so hits can be traced back to the injection point. [bare] drops the
     * server location from the payload string.
     */
    fun generate(count: Int, customData: String?, bare: Boolean): List<GeneratedPayload>

    /** Poll Burp, merge into the local log, return the whole log oldest-first. */
    fun poll(): List<CollaboratorInteraction>

    /** Payloads minted through this source since it was built, oldest first. */
    fun issued(): List<GeneratedPayload>
}

/** One minted payload. [id] is the payload's interaction id — the first DNS label. */
data class GeneratedPayload(val id: String, val value: String, val customData: String?)

/**
 * One out-of-band hit, flattened to plain data.
 *
 * [id] is the *payload* id, not a per-interaction identity — a single payload
 * that triggers a DNS lookup and then an HTTP fetch yields two records sharing
 * one id. [subtype] carries the DNS query type / HTTP protocol / SMTP
 * protocol. [detail] is the raw HTTP request or SMTP conversation; DNS carries
 * none (the wire query is a binary packet, not worth rendering).
 */
data class CollaboratorInteraction(
    val id: String,
    val type: String,
    val subtype: String?,
    val timestamp: Instant,
    val clientIp: String,
    val clientPort: Int,
    val customData: String?,
    val detail: String?,
)

/**
 * Thrown when Collaborator can't be reached — Community edition, or disabled
 * under `Settings → Project → Collaborator`. Carries a message meant to be
 * shown to the model verbatim.
 */
class CollaboratorUnavailable(cause: Throwable?) : RuntimeException(
    "Burp Collaborator is unavailable — it needs Burp Suite Professional and must be enabled " +
        "under Settings → Project → Collaborator" +
        (cause?.message?.takeIf { it.isNotBlank() }?.let { " ($it)" } ?: ""),
    cause,
)

/**
 * Append-only interaction store with idempotent merge. Kept separate from the
 * Burp plumbing so the dedup rule is unit-testable.
 */
class InteractionLog {
    private val byKey = LinkedHashMap<String, CollaboratorInteraction>()

    /**
     * Merge [records] in arrival order, returning how many were new.
     *
     * Two interactions of the same type from the same client at the same
     * millisecond on the same payload collapse into one. That is a real (if
     * remote) possibility — a resolver retrying an A lookup inside one
     * millisecond — and the alternative, treating every poll's rows as new,
     * duplicates the entire log on each call if Burp replays rather than
     * drains. Under-counting a burst is the cheaper failure.
     */
    @Synchronized
    fun merge(records: List<CollaboratorInteraction>): Int {
        var added = 0
        for (r in records) {
            val key = "${r.id}|${r.type}|${r.timestamp.toEpochMilli()}|${r.clientIp}|${r.clientPort}"
            if (byKey.put(key, r) == null) added += 1
        }
        return added
    }

    @Synchronized
    fun all(): List<CollaboratorInteraction> = byKey.values.toList()

    @Synchronized
    fun size(): Int = byKey.size
}

/** Production [CollaboratorSource]. */
class BurpCollaboratorSource(
    private val api: MontoyaApi,
    private val loadSecret: () -> String?,
    private val saveSecret: (String) -> Unit,
) : CollaboratorSource {

    private val log = InteractionLog()
    private val minted = mutableListOf<GeneratedPayload>()

    @Volatile
    private var cached: CollaboratorClient? = null

    /**
     * Lazily acquire the client. Deliberately *not* `by lazy` at construction
     * time: the registry is built while the server starts, long before any
     * tool call, and a Collaborator that is disabled then may be enabled
     * later. Failure is not cached, so the next call retries.
     */
    private fun client(): CollaboratorClient {
        cached?.let { return it }
        synchronized(this) {
            cached?.let { return it }
            val c = try {
                acquire()
            } catch (t: Throwable) {
                throw CollaboratorUnavailable(t)
            }
            cached = c
            return c
        }
    }

    private fun acquire(): CollaboratorClient {
        val stored = loadSecret()?.takeIf { it.isNotBlank() }
        if (stored != null) {
            // A stored key goes stale when the project's Collaborator server
            // changes. Probe with server() so a dud client fails here rather
            // than on the first payload.
            val restored = runCatching {
                api.collaborator().restoreClient(SecretKey.secretKey(stored)).also { it.server() }
            }.getOrNull()
            if (restored != null) return restored
        }
        val fresh = api.collaborator().createClient()
        fresh.server()
        runCatching { saveSecret(fresh.getSecretKey().toString()) }
        return fresh
    }

    override fun server(): String = try {
        client().server().address()
    } catch (e: CollaboratorUnavailable) {
        throw e
    } catch (t: Throwable) {
        throw CollaboratorUnavailable(t)
    }

    override fun generate(count: Int, customData: String?, bare: Boolean): List<GeneratedPayload> {
        val c = client()
        val opts: Array<PayloadOption> =
            if (bare) arrayOf(PayloadOption.WITHOUT_SERVER_LOCATION) else emptyArray()
        val out = mutableListOf<GeneratedPayload>()
        for (i in 0 until count) {
            val data = customData?.let { tagFor(it, i, count) }
            val p = try {
                if (data != null) c.generatePayload(data, *opts) else c.generatePayload(*opts)
            } catch (t: Throwable) {
                throw CollaboratorUnavailable(t)
            }
            val g = GeneratedPayload(id = p.id().toString(), value = p.toString(), customData = data)
            out += g
            synchronized(minted) { minted += g }
        }
        return out
    }

    override fun poll(): List<CollaboratorInteraction> {
        val c = client()
        val fetched = try {
            c.getAllInteractions()
        } catch (t: Throwable) {
            throw CollaboratorUnavailable(t)
        }
        log.merge(fetched.mapNotNull { runCatching { toRecord(it) }.getOrNull() })
        return log.all()
    }

    override fun issued(): List<GeneratedPayload> = synchronized(minted) { minted.toList() }

    private fun toRecord(i: Interaction): CollaboratorInteraction {
        val dns = i.dnsDetails().orElse(null)
        val http = i.httpDetails().orElse(null)
        val smtp = i.smtpDetails().orElse(null)
        return CollaboratorInteraction(
            id = i.id().toString(),
            type = i.type().name,
            subtype = dns?.queryType()?.name
                ?: http?.protocol()?.name
                ?: smtp?.protocol()?.name,
            timestamp = runCatching { i.timeStamp().toInstant() }.getOrNull() ?: Instant.EPOCH,
            clientIp = runCatching { i.clientIp().hostAddress }.getOrNull().orEmpty(),
            clientPort = runCatching { i.clientPort() }.getOrDefault(0),
            customData = i.customData().orElse(null),
            // DNS carries only the raw wire packet — nothing readable to show,
            // and the query type is already in `subtype`.
            detail = when {
                http != null -> runCatching { http.requestResponse()?.request()?.toString() }.getOrNull()
                smtp != null -> runCatching { smtp.conversation() }.getOrNull()
                else -> null
            }?.takeIf { it.isNotBlank() },
        )
    }

    companion object {
        /** Burp's ceiling on custom data. */
        const val CUSTOM_DATA_MAX: Int = 16

        /**
         * Per-payload custom data. With one payload the tag is used as given;
         * with several, a 1-based index is appended (truncating the base if
         * needed) so each injection point stays distinguishable.
         */
        fun tagFor(base: String, index: Int, count: Int): String {
            if (count <= 1) return base.take(CUSTOM_DATA_MAX)
            val suffix = (index + 1).toString()
            return base.take(CUSTOM_DATA_MAX - suffix.length) + suffix
        }
    }
}

/** Test-only [CollaboratorSource]. Mints predictable payloads; interactions are pushed in. */
class FakeCollaboratorSource(
    private val serverAddress: String = "oastify.com",
) : CollaboratorSource {

    private val log = InteractionLog()
    private val minted = mutableListOf<GeneratedPayload>()
    private val pending = mutableListOf<CollaboratorInteraction>()
    private var seq = 0

    /** When set, every call throws [CollaboratorUnavailable] — the Community-edition path. */
    var unavailable: Boolean = false

    override fun server(): String {
        if (unavailable) throw CollaboratorUnavailable(null)
        return serverAddress
    }

    override fun generate(count: Int, customData: String?, bare: Boolean): List<GeneratedPayload> {
        if (unavailable) throw CollaboratorUnavailable(null)
        return (0 until count).map { i ->
            val id = "pay%03d".format(seq++)
            val g = GeneratedPayload(
                id = id,
                value = if (bare) id else "$id.$serverAddress",
                customData = customData?.let { BurpCollaboratorSource.tagFor(it, i, count) },
            )
            minted += g
            g
        }
    }

    override fun poll(): List<CollaboratorInteraction> {
        if (unavailable) throw CollaboratorUnavailable(null)
        log.merge(pending.toList())
        return log.all()
    }

    override fun issued(): List<GeneratedPayload> = minted.toList()

    /** Queue an interaction to be returned by the next (and every later) [poll]. */
    fun push(i: CollaboratorInteraction) { pending += i }
}
