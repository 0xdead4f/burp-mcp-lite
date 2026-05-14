package io.github.dead4f.burpmcplite.ui

import java.awt.Cursor
import java.awt.Desktop
import java.awt.event.ActionEvent
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import java.awt.font.TextAttribute
import java.net.URI
import javax.swing.AbstractAction
import javax.swing.JLabel
import javax.swing.KeyStroke
import javax.swing.UIManager

/** Clickable URL label — opens in the system browser; underlined; keyboard accessible. */
class Anchor(text: String, private val url: String) : JLabel(text) {

    init {
        font = font.deriveFont(mapOf(TextAttribute.UNDERLINE to TextAttribute.UNDERLINE_ON))
        foreground = anchorColor()
        cursor = Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
        isFocusable = true

        val open = object : AbstractAction() {
            override fun actionPerformed(e: ActionEvent?) {
                try {
                    if (Desktop.isDesktopSupported()) Desktop.getDesktop().browse(URI(url))
                } catch (_: Exception) { /* swallow */ }
            }
        }
        actionMap.put("pressed", open)
        inputMap.put(KeyStroke.getKeyStroke("released SPACE"), "pressed")

        addMouseListener(object : MouseAdapter() {
            override fun mouseEntered(e: MouseEvent) { foreground = anchorHover() }
            override fun mouseExited(e: MouseEvent) { foreground = anchorColor() }
            override fun mouseClicked(e: MouseEvent) { open.actionPerformed(null) }
        })
    }

    override fun updateUI() {
        super.updateUI()
        foreground = anchorColor()
    }

    private fun anchorColor() = UIManager.getColor("Burp.anchorForeground") ?: Design.Colors.primary
    private fun anchorHover() = UIManager.getColor("Burp.anchorHoverForeground") ?: Design.Colors.primary.darker()
}
