package io.github.dead4f.burpmcplite.server

import burp.api.montoya.MontoyaApi
import io.github.dead4f.burpmcplite.snapshot.BurpHistorySource
import io.github.dead4f.burpmcplite.snapshot.BurpSiteMapSource
import io.github.dead4f.burpmcplite.tools.ToolRegistry
import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.engine.*
import io.ktor.server.netty.*
import io.ktor.server.plugins.cors.routing.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.modelcontextprotocol.kotlin.sdk.Implementation
import io.modelcontextprotocol.kotlin.sdk.ServerCapabilities
import io.modelcontextprotocol.kotlin.sdk.server.Server
import io.modelcontextprotocol.kotlin.sdk.server.ServerOptions
import io.modelcontextprotocol.kotlin.sdk.server.mcp
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/** Lifecycle state for the embedded MCP server. */
sealed class ServerState {
    data object Starting : ServerState()
    data object Running : ServerState()
    data object Stopping : ServerState()
    data object Stopped : ServerState()
    data class Failed(val exception: Throwable) : ServerState()
}

/**
 * Starts and stops the embedded Ktor MCP server. Single executor thread —
 * Ktor's start/stop calls can block, and we don't want to wedge the Burp
 * UI thread when toggling the config checkbox.
 */
class ServerManager(private val api: MontoyaApi) {

    companion object {
        const val SERVER_NAME: String = "burp-mcp-lite"
        const val SERVER_VERSION: String = "0.3.1"
    }

    private var server: EmbeddedServer<*, *>? = null
    private val executor = Executors.newSingleThreadExecutor { r ->
        Thread(r, "burp-mcp-lite-server").apply { isDaemon = true }
    }

    fun start(config: Config, callback: (ServerState) -> Unit) {
        callback(ServerState.Starting)
        executor.submit {
            try {
                server?.stop(1000, 5000)
                server = null

                val source = BurpHistorySource(api)
                val siteMap = BurpSiteMapSource(api)
                val registry = ToolRegistry.build(source, siteMap)

                val mcpServer = Server(
                    serverInfo = Implementation(SERVER_NAME, SERVER_VERSION),
                    options = ServerOptions(
                        capabilities = ServerCapabilities(
                            tools = ServerCapabilities.Tools(listChanged = false),
                        ),
                    ),
                )
                mcpServer.registerLiteTools(registry)

                val expectedPort = config.port
                server = embeddedServer(Netty, port = expectedPort, host = config.host) {
                    install(CORS) {
                        allowHost("localhost:$expectedPort")
                        allowHost("127.0.0.1:$expectedPort")
                        allowMethod(HttpMethod.Get)
                        allowMethod(HttpMethod.Post)
                        allowHeader(HttpHeaders.ContentType)
                        allowHeader(HttpHeaders.Accept)
                        allowHeader("Last-Event-ID")
                        allowCredentials = false
                        allowNonSimpleContentTypes = true
                        maxAgeInSeconds = 3600
                    }

                    intercept(ApplicationCallPipeline.Call) {
                        val origin = call.request.header("Origin")
                        val host = call.request.header("Host")
                        val referer = call.request.header("Referer")
                        val ua = call.request.header("User-Agent")

                        if (origin != null && !Security.isValidOrigin(origin)) {
                            api.logging().logToOutput("[burp-mcp-lite] blocked origin: $origin")
                            call.respond(HttpStatusCode.Forbidden); return@intercept
                        } else if (Security.isBrowserRequest(ua)) {
                            api.logging().logToOutput("[burp-mcp-lite] blocked browser request (no Origin)")
                            call.respond(HttpStatusCode.Forbidden); return@intercept
                        }
                        if (host != null && !Security.isValidHost(host, expectedPort)) {
                            api.logging().logToOutput("[burp-mcp-lite] blocked host: $host")
                            call.respond(HttpStatusCode.Forbidden); return@intercept
                        }
                        if (referer != null && !Security.isValidReferer(referer)) {
                            api.logging().logToOutput("[burp-mcp-lite] blocked referer: $referer")
                            call.respond(HttpStatusCode.Forbidden); return@intercept
                        }

                        call.response.header("X-Frame-Options", "DENY")
                        call.response.header("X-Content-Type-Options", "nosniff")
                        call.response.header("Referrer-Policy", "same-origin")
                        call.response.header("Content-Security-Policy", "default-src 'none'")
                    }

                    mcp { mcpServer }
                    StreamableHttp.install(this, registry, SERVER_NAME, SERVER_VERSION, api.logging())
                }.apply { start(wait = false) }

                api.logging().logToOutput(
                    "[burp-mcp-lite] listening on ${config.host}:${config.port} " +
                        "— /mcp (HTTP) and /sse (SSE)"
                )
                callback(ServerState.Running)
            } catch (e: Exception) {
                api.logging().logToError(e)
                callback(ServerState.Failed(e))
            }
        }
    }

    fun stop(callback: (ServerState) -> Unit) {
        callback(ServerState.Stopping)
        executor.submit {
            try {
                server?.stop(1000, 5000)
                server = null
                api.logging().logToOutput("[burp-mcp-lite] stopped")
                callback(ServerState.Stopped)
            } catch (e: Exception) {
                api.logging().logToError(e)
                callback(ServerState.Failed(e))
            }
        }
    }

    fun shutdown() {
        server?.stop(1000, 5000)
        server = null
        executor.shutdown()
        executor.awaitTermination(10, TimeUnit.SECONDS)
    }
}
