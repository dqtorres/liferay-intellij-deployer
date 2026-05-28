package com.liferay.deployer.ui

import com.intellij.openapi.ui.DialogWrapper
import com.intellij.openapi.ui.ValidationInfo
import com.intellij.ui.components.JBTextField
import com.liferay.deployer.config.Environment
import java.awt.*
import javax.swing.*

class EnvironmentDialog(existing: Environment?) : DialogWrapper(true) {

    private val env           = existing?.deepCopy() ?: Environment()
    private val nameField     = JBTextField(env.name, 22)
    private var pickedColor   = env.awtColor()
    private val colorPreview  = JLabel().apply {
        isOpaque        = true
        background      = pickedColor
        preferredSize   = Dimension(36, 22)
        border          = BorderFactory.createLineBorder(Color.GRAY)
        toolTipText     = "Click 'Choose…' to change"
    }

    init {
        title = if (existing == null) "Add Environment" else "Edit Environment"
        init()
    }

    override fun createCenterPanel(): JComponent {
        val chooseBtn = JButton("Choose…").apply {
            addActionListener {
                val chosen = JColorChooser.showDialog(this, "Choose Color", pickedColor)
                if (chosen != null) {
                    pickedColor       = chosen
                    colorPreview.background = chosen
                }
            }
        }

        val row = JPanel(FlowLayout(FlowLayout.LEFT, 6, 0)).apply {
            add(colorPreview)
            add(chooseBtn)
        }

        return JPanel(GridBagLayout()).apply {
            val gbc = GridBagConstraints().apply {
                insets  = Insets(4, 4, 4, 4)
                anchor  = GridBagConstraints.WEST
            }
            gbc.gridx = 0; gbc.gridy = 0; add(JLabel("Name:"), gbc)
            gbc.gridx = 1; gbc.fill = GridBagConstraints.HORIZONTAL; gbc.weightx = 1.0
            add(nameField, gbc)

            gbc.gridx = 0; gbc.gridy = 1; gbc.fill = GridBagConstraints.NONE; gbc.weightx = 0.0
            add(JLabel("Color:"), gbc)
            gbc.gridx = 1
            add(row, gbc)
        }
    }

    override fun doValidate(): ValidationInfo? =
        if (nameField.text.isBlank()) ValidationInfo("Name is required", nameField) else null

    fun getEnvironment(): Environment {
        env.name     = nameField.text.trim()
        env.colorHex = "#%02X%02X%02X".format(pickedColor.red, pickedColor.green, pickedColor.blue)
        return env
    }
}
