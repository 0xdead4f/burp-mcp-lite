package io.github.dead4f.burpmcplite.ui

import javax.swing.BorderFactory
import javax.swing.BoxLayout
import javax.swing.JPanel

/**
 * Base class for the bordered-surface "card" panels used down the right
 * column. Matches the look of upstream's `ServerConfigurationPanel` etc.
 */
abstract class CardPanel : JPanel() {
    init {
        layout = BoxLayout(this, BoxLayout.Y_AXIS)
        applySurface()
        alignmentX = LEFT_ALIGNMENT
    }

    override fun updateUI() {
        super.updateUI()
        applySurface()
    }

    private fun applySurface() {
        background = Design.Colors.surface
        border = BorderFactory.createCompoundBorder(
            BorderFactory.createLineBorder(Design.Colors.outlineVariant, 1),
            BorderFactory.createEmptyBorder(Design.Spacing.MD, Design.Spacing.MD, Design.Spacing.MD, Design.Spacing.MD),
        )
    }
}
