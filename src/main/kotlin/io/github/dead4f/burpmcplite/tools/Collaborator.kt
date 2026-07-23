package io.github.dead4f.burpmcplite.tools

import io.github.dead4f.burpmcplite.format.Render
import io.github.dead4f.burpmcplite.format.Slice
import io.github.dead4f.burpmcplite.snapshot.CollaboratorInteraction
import io.github.dead4f.burpmcplite.snapshot.CollaboratorSource
import io.github.dead4f.burpmcplite.snapshot.CollaboratorUnavailable
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class CollaboratorPayloadArgs(
    /** How many payloads to mint in one call. Default 1, max 20. */
    val count: Int? = null,
    /** Alphanumeric tag echoed back on every interaction. Max 16 chars. */
    @SerialName("custom_data") val customData: String? = null,
    /** Emit the payload without the server location (advanced). */
    val bare: Boolean? = null,
)

@Serializable
data class CollaboratorLogArgs(
    /** Payload id, or the whole payload string / URL / email — parsed down to the id. */
    val payload: String? = null,
    /** `dns`, `http`, `smtp` — one, or a comma-separated list. */
    val type: String? = null,
    /**
     * Slice spec for the raw HTTP request / SMTP conversation behind each hit:
     * "none" (default), "auto", "full", "head:N", "tail:N", "/regex/".
     */
    val detail: String? = null,
    val context: Int? = null,
    val limit: Int? = null,
    val offset: Int? = null,
    val order: String? = null,
    val format: String? = null,
)

/**
 * Mints Burp Collaborator payloads for out-of-band testing.
 *
 * The lean angle over the official server's `generate_collaborator_payload`
 * is [CollaboratorPayloadArgs.count]: peppering ten injection points costs one
 * call and one table instead of ten round-trips, and with `custom_data` set
 * each payload carries its own index so a hit names the parameter that fired.
 */
object CollaboratorPayloadTool {
    private const val MAX_COUNT = 20
    private const val CUSTOM_DATA_MAX = 16

    fun run(source: CollaboratorSource, args: CollaboratorPayloadArgs): String {
        val count = args.count ?: 1
        if (count < 1 || count > MAX_COUNT) {
            return Render.errorLine("count must be between 1 and $MAX_COUNT (got $count)")
        }
        val customData = args.customData?.trim()?.takeIf { it.isNotEmpty() }
        if (customData != null) {
            // Burp rejects these itself, with an exception that says less.
            if (!customData.all { it.isLetterOrDigit() }) {
                return Render.errorLine("custom_data must be alphanumeric (got \"$customData\")")
            }
            if (customData.length > CUSTOM_DATA_MAX) {
                return Render.errorLine(
                    "custom_data is limited to $CUSTOM_DATA_MAX characters (got ${customData.length})",
                )
            }
        }

        return try {
            val payloads = source.generate(count, customData, args.bare ?: false)
            Render.renderPayloads(
                server = runCatching { source.server() }.getOrDefault("?"),
                rows = payloads.map { Render.PayloadRow(it.id, it.value, it.customData) },
            )
        } catch (e: CollaboratorUnavailable) {
            Render.errorLine(e.message ?: "Burp Collaborator is unavailable")
        }
    }
}

/**
 * Reads the Collaborator interaction log.
 *
 * Polls Burp and prints a compact table — one line per hit. The raw HTTP
 * request or SMTP conversation behind a hit is withheld unless `detail=` asks
 * for it, which is the whole token saving over the official server's
 * `get_collaborator_interactions` (that one JSON-serializes every field of
 * every interaction, raw HTTP request *and* response included, on every poll).
 *
 * Interactions accumulate across calls — see [CollaboratorSource] — so polling
 * repeatedly is safe and never drops an earlier hit.
 */
object CollaboratorLogTool {
    private const val AUTO_FULL_THRESHOLD = 2 * 1024

    fun run(source: CollaboratorSource, args: CollaboratorLogArgs): String {
        val limit = args.limit ?: 20
        val offset = args.offset ?: 0
        val order = (args.order ?: "latest").lowercase()
        val format = (args.format ?: "text").lowercase()
        val detailSpec = (args.detail ?: "none").trim()
        val context = args.context ?: 1

        val all: List<CollaboratorInteraction> = try {
            source.poll()
        } catch (e: CollaboratorUnavailable) {
            return Render.errorLine(e.message ?: "Burp Collaborator is unavailable")
        }

        val wantId = args.payload?.let { payloadId(it) }?.takeIf { it.isNotEmpty() }
        val wantTypes = args.type
            ?.split(',')
            ?.map { it.trim().uppercase() }
            ?.filter { it.isNotEmpty() }
            ?.toSet()
            ?.takeIf { it.isNotEmpty() }

        val filtered = all.filter { i ->
            (wantId == null || i.id.equals(wantId, ignoreCase = true)) &&
                (wantTypes == null || i.type.uppercase() in wantTypes)
        }
        val ordered = if (order == "latest") filtered.asReversed() else filtered
        val total = ordered.size
        val page = ordered.drop(offset).take(maxOf(0, limit))

        val showDetail = detailSpec.lowercase() != "none"
        var anyAutoTruncated = false
        val rows = page.map { i ->
            val detail = if (!showDetail || i.detail.isNullOrEmpty()) null else {
                val spec = resolveDetailSpec(detailSpec, i.detail.length)
                if (spec != detailSpec && detailSpec.lowercase() == "auto") anyAutoTruncated = true
                Slice.sliceBody(i.detail, spec, context).text
            }
            Render.InteractionRow(
                time = Render.formatInstant(i.timestamp),
                type = typeLabel(i),
                client = i.clientIp,
                payload = i.id,
                customData = i.customData,
                detail = detail,
            )
        }

        if (format == "json") return Render.renderInteractionsNdjson(rows, showDetail)

        val note = when {
            anyAutoTruncated -> "... (auto-truncated; pass detail=\"full\" for the whole capture)"
            !showDetail && page.any { !it.detail.isNullOrEmpty() } ->
                "(raw capture withheld — pass detail=\"auto\" to see it)"
            else -> null
        }
        return Render.renderInteractions(rows, total, offset, source.issued().size, note)
    }

    /** `DNS(A)`, `HTTP`, `SMTP(SMTPS)` — subtype only where it adds something. */
    private fun typeLabel(i: CollaboratorInteraction): String {
        val sub = i.subtype?.takeIf { it.isNotBlank() } ?: return i.type
        // HTTP's subtype is the protocol version, already implied by the type.
        if (i.type.equals("HTTP", ignoreCase = true)) return i.type
        return "${i.type}($sub)"
    }

    private fun resolveDetailSpec(spec: String, size: Int): String {
        if (spec.lowercase() == "auto") {
            return if (size <= AUTO_FULL_THRESHOLD) "full" else "head:20"
        }
        return spec
    }

    /**
     * Reduce whatever the caller passed to the payload id — the first DNS
     * label. Accepts `abc123`, `abc123.oastify.com`, `https://abc123.oastify.com/x`
     * and `user@abc123.oastify.com`, because a payload gets pasted back in all
     * of those shapes depending on where it was planted.
     */
    fun payloadId(raw: String): String = raw.trim()
        .substringAfter("://")
        .substringBefore('/')
        .substringAfterLast('@')
        .substringBefore('.')
        .lowercase()
}
