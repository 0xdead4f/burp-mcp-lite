package io.github.dead4f.burpmcplite.ui

import java.awt.BorderLayout
import java.awt.GridBagConstraints
import java.awt.GridBagLayout
import javax.swing.BorderFactory
import javax.swing.BoxLayout
import javax.swing.JPanel
import javax.swing.JScrollPane

/**
 * Renders [leftPanel] and [rightPanel] side-by-side when there's room, and
 * stacks them vertically when there isn't. Mirrors upstream's
 * `ResponsiveColumnsPanel`.
 */
class ResponsiveColumnsPanel(
    private val leftPanel: JPanel,
    private val rightPanel: JScrollPane,
) : JPanel() {

    private enum class Layout { SINGLE_COLUMN, TWO_COLUMNS }
    private enum class PaddingSize { SMALL, LARGE }

    private val minWidthForTwoColumns = 900
    private val minWidthForLargePadding = 700
    private var lastLayout = Layout.SINGLE_COLUMN
    private var lastPaddingSize = PaddingSize.SMALL
    private var initialized = false

    init {
        initialized = true
        applyLayout()
    }

    override fun updateUI() {
        super.updateUI()
        if (initialized) applyLayout()
    }

    override fun doLayout() {
        super.doLayout()
        val nextLayout = if (width >= minWidthForTwoColumns) Layout.TWO_COLUMNS else Layout.SINGLE_COLUMN
        val nextPad = if (width >= minWidthForLargePadding) PaddingSize.LARGE else PaddingSize.SMALL
        if (nextLayout != lastLayout || nextPad != lastPaddingSize) {
            lastLayout = nextLayout
            lastPaddingSize = nextPad
            applyLayout()
        }
    }

    private fun applyLayout() {
        removeAll()
        val pad = if (lastPaddingSize == PaddingSize.LARGE) Design.Spacing.LG else Design.Spacing.SM

        (rightPanel.viewport.view as? JPanel)?.border =
            BorderFactory.createEmptyBorder(pad, pad, pad, pad)

        when (lastLayout) {
            Layout.TWO_COLUMNS -> {
                layout = GridBagLayout()
                val c = GridBagConstraints().apply { fill = GridBagConstraints.BOTH; weighty = 1.0 }
                c.gridx = 0; c.gridy = 0; c.weightx = 0.35; add(leftPanel, c)
                c.gridx = 1; c.weightx = 0.65; add(rightPanel, c)
            }
            Layout.SINGLE_COLUMN -> {
                layout = BorderLayout()
                val stack = JPanel().apply {
                    layout = BoxLayout(this, BoxLayout.Y_AXIS)
                    background = Design.Colors.surface
                }
                val headerWrap = JPanel(BorderLayout()).apply {
                    isOpaque = false
                    border = BorderFactory.createEmptyBorder(pad, pad, Design.Spacing.MD, pad)
                    add(leftPanel, BorderLayout.CENTER)
                }
                stack.add(headerWrap)
                val scrollWrap = JPanel(BorderLayout()).apply {
                    isOpaque = false
                    add(rightPanel, BorderLayout.CENTER)
                }
                stack.add(scrollWrap)
                add(stack, BorderLayout.CENTER)
            }
        }
        revalidate()
        repaint()
    }
}
