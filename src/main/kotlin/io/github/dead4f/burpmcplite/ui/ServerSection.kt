package io.github.dead4f.burpmcplite.ui

import io.github.dead4f.burpmcplite.server.ServerState
import java.awt.Color
import java.awt.FlowLayout
import javax.swing.Box.createHorizontalStrut
import javax.swing.Box.createVerticalStrut
import javax.swing.JLabel
import javax.swing.JPanel

/**
 * The server card — enable toggle + live status line. The status text
 * reflects the most recent [ServerState] we've heard about.
 */
class ServerSection(val enabledToggle: ToggleSwitch) : CardPanel() {

    private val statusLabel = JLabel("idle").apply {
        font = Design.Typography.bodyMedium
        foreground = Design.Colors.onSurfaceVariant
    }

    private val statusDot = StatusDot()

    init {
        add(Design.createSectionLabel("Server"))
        add(createVerticalStrut(Design.Spacing.MD))

        val toggleRow = JPanel(FlowLayout(FlowLayout.LEFT, 0, 4)).apply {
            isOpaque = false
            alignmentX = LEFT_ALIGNMENT
            add(JLabel("Enabled").apply {
                font = Design.Typography.bodyLarge
                foreground = Design.Colors.onSurface
            })
            add(createHorizontalStrut(Design.Spacing.MD))
            add(enabledToggle)
        }
        add(toggleRow)
        add(createVerticalStrut(Design.Spacing.SM))

        val statusRow = JPanel(FlowLayout(FlowLayout.LEFT, 0, 0)).apply {
            isOpaque = false
            alignmentX = LEFT_ALIGNMENT
            add(statusDot)
            add(createHorizontalStrut(Design.Spacing.SM))
            add(statusLabel)
        }
        add(statusRow)
    }

    fun setStatus(state: ServerState, endpoint: String?) {
        val (text, color) = when (state) {
            ServerState.Starting -> "starting…" to Design.Colors.onSurfaceVariant
            ServerState.Running -> ("running on $endpoint" to Design.Colors.success)
            ServerState.Stopping -> "stopping…" to Design.Colors.onSurfaceVariant
            ServerState.Stopped -> "stopped" to Design.Colors.onSurfaceVariant
            is ServerState.Failed -> ("failed: ${state.exception.message ?: state.exception.javaClass.simpleName}" to Design.Colors.error)
        }
        statusLabel.text = text
        statusLabel.foreground = color
        statusDot.color = color
        statusDot.repaint()
    }
}

private class StatusDot : JPanel() {
    var color: Color = Design.Colors.onSurfaceVariant

    init {
        isOpaque = false
        preferredSize = java.awt.Dimension(10, 10)
    }

    override fun paintComponent(g: java.awt.Graphics) {
        super.paintComponent(g)
        val g2 = g.create() as java.awt.Graphics2D
        g2.setRenderingHint(java.awt.RenderingHints.KEY_ANTIALIASING, java.awt.RenderingHints.VALUE_ANTIALIAS_ON)
        g2.color = color
        g2.fillOval(0, 1, 8, 8)
        g2.dispose()
    }
}
