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

    companion object {
        const val DEFAULT_HOST: String = "127.0.0.1"
        const val DEFAULT_PORT: Int = 9876
        private const val KEY_ENABLED = "burp-mcp-lite.enabled"
        private const val KEY_HOST = "burp-mcp-lite.host"
        private const val KEY_PORT = "burp-mcp-lite.port"
    }
}
