package io.github.dead4f.burpmcplite.ui

import java.awt.Dimension
import java.awt.GridBagConstraints
import java.awt.GridBagLayout
import java.awt.Insets
import javax.swing.Box.createVerticalStrut
import javax.swing.JLabel
import javax.swing.JPanel
import javax.swing.JTextField
import javax.swing.event.DocumentEvent
import javax.swing.event.DocumentListener

/**
 * The advanced-options card — host + port fields. Mirrors upstream's
 * `AdvancedOptionsPanel`: changes show the [restartNotice] until the user
 * toggles the server off and on again.
 */
class AdvancedSection(
    val hostField: JTextField,
    val portField: JTextField,
    private val restartNotice: WarningLabel,
) : CardPanel() {

    init {
        add(Design.createSectionLabel("Advanced Options"))
        add(createVerticalStrut(Design.Spacing.MD))
        add(buildForm())
        add(createVerticalStrut(Design.Spacing.SM))
        add(restartNotice)
        watchForChanges()
    }

    private fun buildForm(): JPanel {
        val form = JPanel(GridBagLayout()).apply {
            isOpaque = false
            alignmentX = LEFT_ALIGNMENT
        }
        val gbc = GridBagConstraints().apply {
            insets = Insets(Design.Spacing.SM, 0, Design.Spacing.SM, Design.Spacing.MD)
            anchor = GridBagConstraints.WEST
        }
        addRow(form, gbc, 0, "Server host:", hostField)
        addRow(form, gbc, 1, "Server port:", portField)
        return form
    }

    private fun addRow(form: JPanel, gbc: GridBagConstraints, row: Int, label: String, field: JTextField) {
        gbc.gridx = 0; gbc.gridy = row
        gbc.fill = GridBagConstraints.NONE
        gbc.weightx = 0.0
        gbc.insets = Insets(Design.Spacing.SM, 0, Design.Spacing.SM, Design.Spacing.MD)
        form.add(JLabel(label).apply {
            font = Design.Typography.bodyLarge
            foreground = Design.Colors.onSurface
        }, gbc)

        gbc.gridx = 1
        gbc.fill = GridBagConstraints.HORIZONTAL
        gbc.weightx = 1.0
        gbc.insets = Insets(Design.Spacing.SM, 0, Design.Spacing.SM, 0)
        field.preferredSize = Dimension(200, 32)
        field.font = Design.Typography.bodyLarge
        form.add(field, gbc)
    }

    private fun watchForChanges() {
        val listener = object : DocumentListener {
            override fun insertUpdate(e: DocumentEvent?) { restartNotice.isVisible = true }
            override fun removeUpdate(e: DocumentEvent?) { restartNotice.isVisible = true }
            override fun changedUpdate(e: DocumentEvent?) { restartNotice.isVisible = true }
        }
        hostField.document.addDocumentListener(listener)
        portField.document.addDocumentListener(listener)
    }

    fun setFieldsEnabled(enabled: Boolean) {
        hostField.isEnabled = enabled
        portField.isEnabled = enabled
    }
}
