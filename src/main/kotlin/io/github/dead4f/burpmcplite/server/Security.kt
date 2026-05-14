package io.github.dead4f.burpmcplite.server

import java.net.URI

/**
 * Localhost-binding + Origin/Host/Referer guards, mirroring upstream
 * `mcp-server/KtorServerManager.kt`. The MCP server only listens on
 * 127.0.0.1; these checks defend against DNS rebinding from a browser tab.
 */
object Security {
    private val ALLOWED_HOSTS: Set<String> = setOf("localhost", "127.0.0.1")

    fun isValidOrigin(origin: String): Boolean = runCatching {
        URI(origin).toURL().host.lowercase() in ALLOWED_HOSTS
    }.getOrDefault(false)

    fun isValidHost(host: String, expectedPort: Int): Boolean = runCatching {
        val parts = host.split(":")
        val hostname = parts[0].lowercase()
        if (hostname !in ALLOWED_HOSTS) return@runCatching false
        val port = if (parts.size > 1) parts[1].toIntOrNull() else null
        port == null || port == expectedPort
    }.getOrDefault(false)

    fun isValidReferer(referer: String): Boolean = runCatching {
        URI(referer).toURL().host.lowercase() in ALLOWED_HOSTS
    }.getOrDefault(false)

    private val BROWSER_INDICATORS = listOf(
        "mozilla/", "chrome/", "safari/", "webkit/", "gecko/",
        "firefox/", "edge/", "opera/", "browser",
    )

    fun isBrowserRequest(userAgent: String?): Boolean {
        if (userAgent == null) return false
        val ua = userAgent.lowercase()
        return BROWSER_INDICATORS.any { it in ua }
    }
}
