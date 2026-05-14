package io.github.dead4f.burpmcplite.ui

import java.awt.Color
import javax.swing.JLabel
import javax.swing.UIManager

/** Inline warning label. Hidden by default; flip [isVisible] when you have something to say. */
class WarningLabel(content: String = "") : JLabel(content) {
    init {
        foreground = warnColor()
        isVisible = false
        alignmentX = LEFT_ALIGNMENT
    }

    override fun updateUI() {
        super.updateUI()
        foreground = warnColor()
    }

    private fun warnColor(): Color =
        UIManager.getColor("Burp.warningBarBackground") ?: Design.Colors.warning
}
