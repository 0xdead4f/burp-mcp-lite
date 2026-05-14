package io.github.dead4f.burpmcplite

import burp.api.montoya.BurpExtension
import burp.api.montoya.MontoyaApi
import io.github.dead4f.burpmcplite.server.Config
import io.github.dead4f.burpmcplite.server.ServerManager
import io.github.dead4f.burpmcplite.ui.ConfigUi

/**
 * Burp extension entry point. Wires the config, server manager, and a
 * single MCP tab. Auto-starts the server on load if it was previously
 * enabled (the default for a fresh install).
 */
@Suppress("unused")
class BurpMcpLiteExtension : BurpExtension {

    override fun initialize(api: MontoyaApi) {
        api.extension().setName("Burp MCP Lite")

        val config = Config(api.persistence().extensionData())
        val manager = ServerManager(api)
        val ui = ConfigUi(config)

        ui.onEnabledToggled { enabled ->
            if (enabled) manager.start(config, ui::updateState)
            else manager.stop(ui::updateState)
        }

        api.userInterface().registerSuiteTab("MCP Lite", ui.component)

        api.extension().registerUnloadingHandler {
            manager.shutdown()
        }

        if (config.enabled) {
            manager.start(config, ui::updateState)
        }
    }
}
