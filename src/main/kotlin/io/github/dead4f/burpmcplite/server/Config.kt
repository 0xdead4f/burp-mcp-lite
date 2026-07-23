package io.github.dead4f.burpmcplite.server

import burp.api.montoya.persistence.PersistedObject

/**
 * Persisted server configuration. Backed by Burp's extension persistence,
 * which means values survive Burp restarts.
 */
class Config(private val store: PersistedObject?) {
    var enabled: Boolean
        get() = store?.getBoolean(KEY_ENABLED) ?: true
        set(v) { store?.setBoolean(KEY_ENABLED, v) }

    var host: String
        get() = store?.getString(KEY_HOST) ?: DEFAULT_HOST
        set(v) { store?.setString(KEY_HOST, v) }

    var port: Int
        get() = store?.getInteger(KEY_PORT) ?: DEFAULT_PORT
        set(v) { store?.setInteger(KEY_PORT, v) }

    /**
     * Secret key of the Burp Collaborator client, so `restoreClient` can hand
     * back the same payload namespace (and its interaction history) after a
     * server toggle or a Burp restart. Without this, every restart orphans the
     * payloads already planted in a target. Null until the first payload is
     * minted.
     */
    var collaboratorSecret: String?
        get() = store?.getString(KEY_COLLAB_SECRET)
        set(v) { if (v != null) store?.setString(KEY_COLLAB_SECRET, v) }

    companion object {
        const val DEFAULT_HOST: String = "127.0.0.1"
        const val DEFAULT_PORT: Int = 9876
        private const val KEY_ENABLED = "burp-mcp-lite.enabled"
        private const val KEY_HOST = "burp-mcp-lite.host"
        private const val KEY_PORT = "burp-mcp-lite.port"
        private const val KEY_COLLAB_SECRET = "burp-mcp-lite.collaborator.secret"
    }
}
