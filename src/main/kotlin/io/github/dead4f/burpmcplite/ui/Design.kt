package io.github.dead4f.burpmcplite.ui

import java.awt.Color
import java.awt.Cursor
import java.awt.Dimension
import java.awt.Font
import javax.swing.BorderFactory
import javax.swing.JButton
import javax.swing.JLabel
import javax.swing.UIManager

/**
 * Shared design constants and component factories so every card tracks
 * Burp's current Look & Feel automatically. Mirrors
 * `mcp-server/.../config/Design.kt` — the values lookup via `UIManager` so
 * dark mode / light mode / theme changes propagate without restarting Burp.
 */
object Design {

    object Colors {
        val primary: Color get() = UIManager.getColor("Burp.primaryButtonBackground") ?: Color(0xD86633)
        val onPrimary: Color get() = UIManager.getColor("Burp.primaryButtonForeground") ?: Color.WHITE
        val surface: Color get() = UIManager.getColor("Panel.background") ?: Color(0xFFFBFF)
        val onSurface: Color get() = UIManager.getColor("Label.foreground") ?: Color(0x1A1A1A)
        val onSurfaceVariant: Color get() = UIManager.getColor("Label.disabledForeground") ?: Color(0x666666)
        val outline: Color get() = UIManager.getColor("Component.borderColor") ?: Color(0xCCCCCC)
        val outlineVariant: Color get() = UIManager.getColor("Separator.foreground") ?: Color(0xE0E0E0)
        val error: Color get() = UIManager.getColor("Burp.errorColor") ?: Color(0xB3261E)
        val warning: Color get() = UIManager.getColor("Burp.warningColor") ?: Color(0xF57C00)
        val success: Color get() = UIManager.getColor("Burp.successColor") ?: Color(0x2E7D32)
        val transparent: Color = Color(0, 0, 0, 0)
    }

    object Typography {
        private val baseFont: Font get() = UIManager.getFont("Label.font") ?: Font("Inter", Font.PLAIN, 14)
        private val baseSize: Int get() = baseFont.size

        val headlineMedium: Font get() = baseFont.deriveFont(Font.BOLD, baseSize * 2.0f)
        val titleMedium: Font get() = baseFont.deriveFont(Font.BOLD, baseSize * 1.14f)
        val bodyLarge: Font get() = baseFont.deriveFont(Font.PLAIN, baseSize * 1.14f)
        val bodyMedium: Font get() = baseFont.deriveFont(Font.PLAIN, baseSize.toFloat())
        val labelLarge: Font get() = baseFont.deriveFont(Font.BOLD, baseSize.toFloat())
        val labelMedium: Font get() = baseFont.deriveFont(Font.BOLD, baseSize * 0.86f)
        val mono: Font get() = Font(Font.MONOSPACED, Font.PLAIN, baseSize)
    }

    object Spacing {
        private val baseSize: Int get() = UIManager.getFont("Label.font")?.size ?: 14
        private val scaleFactor: Float get() = baseSize / 14f

        val SM: Int get() = (8 * scaleFactor).toInt().coerceAtLeast(4)
        val MD: Int get() = (16 * scaleFactor).toInt().coerceAtLeast(8)
        val LG: Int get() = (24 * scaleFactor).toInt().coerceAtLeast(12)
        val XL: Int get() = (32 * scaleFactor).toInt().coerceAtLeast(16)
    }

    private fun applyButtonBaseStyle(button: JButton, customSize: Dimension?) {
        button.font = Typography.labelLarge
        button.isFocusPainted = false
        button.cursor = Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
        val metrics = button.getFontMetrics(Typography.labelLarge)
        val textWidth = metrics.stringWidth(button.text ?: "")
        val textHeight = metrics.height
        val w = (textWidth + Spacing.LG * 2).coerceAtLeast(80)
        val h = (textHeight + Spacing.SM * 2 + 4).coerceAtLeast(36)
        button.minimumSize = Dimension(w, h)
        button.preferredSize = customSize ?: Dimension(w, h)
    }

    fun createFilledButton(text: String, customSize: Dimension? = null): JButton =
        object : JButton(text) {
            init { style(); applyButtonBaseStyle(this, customSize) }
            override fun updateUI() { super.updateUI(); style(); applyButtonBaseStyle(this, customSize) }
            private fun style() {
                background = Colors.primary
                foreground = Colors.onPrimary
                border = BorderFactory.createEmptyBorder(Spacing.SM + 2, Spacing.LG, Spacing.SM + 2, Spacing.LG)
            }
        }

    fun createOutlinedButton(text: String, customSize: Dimension? = null): JButton =
        object : JButton(text) {
            init { style(); applyButtonBaseStyle(this, customSize) }
            override fun updateUI() { super.updateUI(); style(); applyButtonBaseStyle(this, customSize) }
            private fun style() {
                background = Colors.surface
                foreground = Colors.primary
                border = BorderFactory.createCompoundBorder(
                    BorderFactory.createLineBorder(Colors.outline, 1),
                    BorderFactory.createEmptyBorder(Spacing.SM + 1, Spacing.LG - 1, Spacing.SM + 1, Spacing.LG - 1),
                )
            }
        }

    fun createSectionLabel(text: String): JLabel =
        object : JLabel(text) {
            init { style(); alignmentX = LEFT_ALIGNMENT }
            override fun updateUI() { super.updateUI(); style() }
            private fun style() {
                font = Typography.titleMedium
                foreground = Colors.onSurface
            }
        }
}
