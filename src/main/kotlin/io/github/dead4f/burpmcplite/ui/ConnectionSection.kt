package io.github.dead4f.burpmcplite.ui

import java.awt.Toolkit
import java.awt.datatransfer.StringSelection
import javax.swing.Box.createHorizontalGlue
import javax.swing.Box.createVerticalStrut
import javax.swing.BoxLayout
import javax.swing.JButton
import javax.swing.JLabel
import javax.swing.JPanel
import javax.swing.SwingUtilities
import javax.swing.Timer

/**
 * The "Connect a client" card. Headline path is the Claude Code one-liner:
 *
 *     claude mcp add --transport http burp-mcp-lite http://host:port/mcp
 *
 * Below that, the raw endpoint URLs (`/mcp` for Streamable HTTP, `/sse`
 * for SSE-only clients) with copy buttons. No stdio-bridge middleware.
 */
class ConnectionSection : CardPanel() {

    private val installCmd = JLabel().apply {
        font = Design.Typography.mono
        foreground = Design.Colors.onSurface
    }
    private val installCopyBtn = Design.createFilledButton("Copy command")

    private val httpUrl = JLabel().apply {
        font = Design.Typography.mono
        foreground = Design.Colors.onSurface
    }
    private val httpCopyBtn = Design.createOutlinedButton("Copy /mcp URL")

    private val sseUrl = JLabel().apply {
        font = Design.Typography.mono
        foreground = Design.Colors.onSurface
    }
    private val sseCopyBtn = Design.createOutlinedButton("Copy /sse URL")

    init {
        add(Design.createSectionLabel("Connect a client"))
        add(createVerticalStrut(Design.Spacing.MD))

        // 1. Claude Code one-liner — primary path.
        add(captionLabel("Install in Claude Code (recommended):"))
        add(createVerticalStrut(Design.Spacing.SM))
        add(row(installCmd, installCopyBtn))
        add(createVerticalStrut(Design.Spacing.LG))

        // 2. Raw endpoints — for clients that configure connections manually.
        add(captionLabel("Streamable HTTP endpoint:"))
        add(createVerticalStrut(Design.Spacing.SM))
        add(row(httpUrl, httpCopyBtn))
        add(createVerticalStrut(Design.Spacing.MD))

        add(captionLabel("SSE endpoint (clients that only speak SSE):"))
        add(createVerticalStrut(Design.Spacing.SM))
        add(row(sseUrl, sseCopyBtn))

        installCopyBtn.addActionListener { copy(installCmd.text, installCopyBtn, "Copy command") }
        httpCopyBtn.addActionListener { copy(httpUrl.text, httpCopyBtn, "Copy /mcp URL") }
        sseCopyBtn.addActionListener { copy(sseUrl.text, sseCopyBtn, "Copy /sse URL") }
    }

    fun setEndpoint(host: String, port: Int) {
        val base = "http://$host:$port"
        installCmd.text = "claude mcp add --transport http burp-mcp-lite $base/mcp"
        httpUrl.text = "$base/mcp"
        sseUrl.text = "$base/sse"
    }

    private fun captionLabel(text: String): JLabel = JLabel(text).apply {
        font = Design.Typography.bodyMedium
        foreground = Design.Colors.onSurfaceVariant
        alignmentX = LEFT_ALIGNMENT
    }

    private fun row(label: JLabel, btn: JButton): JPanel = JPanel().apply {
        layout = BoxLayout(this, BoxLayout.X_AXIS)
        isOpaque = false
        alignmentX = LEFT_ALIGNMENT
        add(label)
        add(createHorizontalGlue())
        add(btn)
    }

    private fun copy(text: String, btn: JButton, original: String) {
        Toolkit.getDefaultToolkit().systemClipboard.setContents(StringSelection(text), null)
        btn.text = "Copied!"
        Timer(1200) {
            SwingUtilities.invokeLater { btn.text = original }
        }.apply { isRepeats = false; start() }
    }
}
