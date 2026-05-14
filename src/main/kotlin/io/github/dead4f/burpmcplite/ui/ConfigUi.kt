package io.github.dead4f.burpmcplite.ui

import io.github.dead4f.burpmcplite.server.Config
import io.github.dead4f.burpmcplite.server.ServerState
import java.awt.BorderLayout
import java.awt.Component
import java.awt.GridBagLayout
import javax.swing.Box.createVerticalBox
import javax.swing.Box.createVerticalGlue
import javax.swing.Box.createVerticalStrut
import javax.swing.BorderFactory
import javax.swing.BoxLayout
import javax.swing.JComponent
import javax.swing.JLabel
import javax.swing.JPanel
import javax.swing.JScrollPane
import javax.swing.JTextField
import javax.swing.SwingUtilities

/**
 * Top-level config UI for the MCP Lite tab. Two-column layout:
 *   left  — title + tagline + learn-more anchor (per upstream).
 *   right — bordered "cards": Server, Connect a client, Advanced Options.
 *
 * The Connection card auto-updates when host/port are persisted on the next
 * enable toggle, mirroring upstream's "Make sure to reinstall after changing
 * server settings" UX.
 */
class ConfigUi(private val config: Config) {

    private val panel = JPanel(BorderLayout())
    val component: JComponent get() = panel

    private val hostField = JTextField(15)
    private val portField = JTextField(5)
    private val restartNotice = WarningLabel("Disable and re-enable the server to apply host/port changes.")
    private val errorNotice = WarningLabel()

    private var suppressEvents = false
    private var enabledListener: ((Boolean) -> Unit)? = null

    private val enabledToggle: ToggleSwitch = ToggleSwitch(config.enabled) { enabled ->
        if (suppressEvents) return@ToggleSwitch
        val parsedPort = portField.text.trim().toIntOrNull()
        val host = hostField.text.trim()
        when {
            host != "localhost" && host != "127.0.0.1" -> {
                errorNotice.text = "Host must be loopback (localhost or 127.0.0.1)."
                errorNotice.isVisible = true
                suppressEvents = true; enabledToggle.setState(false, animate = true); suppressEvents = false
                return@ToggleSwitch
            }
            parsedPort == null || parsedPort !in 1..65535 -> {
                errorNotice.text = "Port must be an integer in 1..65535."
                errorNotice.isVisible = true
                suppressEvents = true; enabledToggle.setState(false, animate = true); suppressEvents = false
                return@ToggleSwitch
            }
            else -> {
                errorNotice.isVisible = false
                restartNotice.isVisible = false
                config.enabled = enabled
                config.host = host
                config.port = parsedPort
                connection.setEndpoint(host, parsedPort)
                enabledListener?.invoke(enabled)
            }
        }
    }

    private val serverCard = ServerSection(enabledToggle)
    private val advancedCard = AdvancedSection(hostField, portField, restartNotice)
    private val connection = ConnectionSection()

    init {
        hostField.text = config.host
        portField.text = config.port.toString()
        connection.setEndpoint(config.host, config.port)
        buildUi()
    }

    fun onEnabledToggled(listener: (Boolean) -> Unit) { enabledListener = listener }

    fun updateState(state: ServerState) {
        SwingUtilities.invokeLater {
            suppressEvents = true
            advancedCard.setFieldsEnabled(state is ServerState.Stopped || state is ServerState.Failed)
            when (state) {
                ServerState.Starting, ServerState.Stopping -> enabledToggle.isEnabled = false
                ServerState.Running -> {
                    enabledToggle.isEnabled = true
                    enabledToggle.setState(true, animate = false)
                }
                ServerState.Stopped -> {
                    enabledToggle.isEnabled = true
                    enabledToggle.setState(false, animate = false)
                }
                is ServerState.Failed -> {
                    enabledToggle.isEnabled = true
                    enabledToggle.setState(false, animate = false)
                    errorNotice.text = "Server failed: ${state.exception.message ?: state.exception.javaClass.simpleName}"
                    errorNotice.isVisible = true
                }
            }
            serverCard.setStatus(state, "${config.host}:${config.port}/sse")
            suppressEvents = false
        }
    }

    private fun buildUi() {
        // Left: header
        val left = JPanel(GridBagLayout())
        val header = createVerticalBox().apply {
            add(JLabel("Burp MCP Lite").apply {
                font = Design.Typography.headlineMedium
                foreground = Design.Colors.onSurface
                alignmentX = Component.CENTER_ALIGNMENT
            })
            add(createVerticalStrut(Design.Spacing.MD))
            add(JLabel("Token-efficient MCP server. Six tools, default-quiet outputs.").apply {
                font = Design.Typography.bodyLarge
                foreground = Design.Colors.onSurfaceVariant
                alignmentX = Component.CENTER_ALIGNMENT
            })
            add(createVerticalStrut(Design.Spacing.MD))
            add(Anchor("Learn more about the Model Context Protocol", "https://modelcontextprotocol.io/introduction")
                .apply { alignmentX = Component.CENTER_ALIGNMENT })
        }
        left.add(header)

        // Right: scrollable stack of cards
        val rightStack = JPanel().apply {
            layout = BoxLayout(this, BoxLayout.Y_AXIS)
            background = Design.Colors.surface
            border = BorderFactory.createEmptyBorder(
                Design.Spacing.LG, Design.Spacing.LG, Design.Spacing.LG, Design.Spacing.LG,
            )
        }
        val rightScroll = JScrollPane(rightStack).apply {
            border = null
            background = Design.Colors.surface
            viewport.background = Design.Colors.surface
            verticalScrollBarPolicy = JScrollPane.VERTICAL_SCROLLBAR_AS_NEEDED
            horizontalScrollBarPolicy = JScrollPane.HORIZONTAL_SCROLLBAR_NEVER
            verticalScrollBar.unitIncrement = 16
        }

        rightStack.add(serverCard)
        rightStack.add(createVerticalStrut(Design.Spacing.LG))
        rightStack.add(connection)
        rightStack.add(createVerticalStrut(Design.Spacing.LG))
        rightStack.add(advancedCard)
        rightStack.add(createVerticalStrut(Design.Spacing.MD))
        rightStack.add(errorNotice)
        rightStack.add(createVerticalGlue())

        val columns = ResponsiveColumnsPanel(left, rightScroll)
        panel.add(columns, BorderLayout.CENTER)
    }
}
