package com.liferay.deployer.ui

import com.intellij.openapi.fileChooser.FileChooserDescriptorFactory
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.openapi.ui.TextFieldWithBrowseButton
import com.intellij.openapi.ui.ValidationInfo
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBPasswordField
import com.intellij.ui.components.JBTextField
import com.liferay.deployer.config.ColorDotIcon
import com.liferay.deployer.config.Environment
import com.liferay.deployer.config.ServerProfile
import com.liferay.deployer.services.SshService
import java.awt.*
import javax.swing.*

class ServerProfileDialog(
    existingProfile: ServerProfile?,
    private val environments: List<Environment> = emptyList()
) : DialogWrapper(true) {

    private val profile = existingProfile?.deepCopy() ?: ServerProfile()

    private val nameField = JBTextField(profile.name, 30)
    private val hostField = JBTextField(profile.host, 25)
    private val portField = JBTextField(profile.port.toString(), 6)
    private val userField = JBTextField(profile.username, 20)

    private val authKeyRadio  = JRadioButton("SSH Key File", profile.authType == "KEY_FILE")
    private val authPassRadio = JRadioButton("Password",     profile.authType == "PASSWORD")
    private val authGroup     = ButtonGroup().also { g -> g.add(authKeyRadio); g.add(authPassRadio) }

    private val keyFileField    = TextFieldWithBrowseButton()
    private val passphraseField = JBPasswordField()
    private val passwordField   = JBPasswordField()
    private val authCards       = CardLayout()
    private val authPanel       = JPanel(authCards)

    private val deployPathField  = JBTextField(profile.remoteDeployPath, 35)
    private val tomcatPathField  = JBTextField(profile.remoteTomcatPath, 35)
    private val osgiStateField   = JBTextField(profile.osgiStatePath, 35)
    private val osgiTmpField     = JBTextField(profile.osgiTmpPath, 35)
    private val logFileField     = JBTextField(profile.logFilePath, 35)
    private val envCombo = JComboBox<Environment?>().apply {
        addItem(null)  // "None"
        environments.forEach { addItem(it) }
        selectedItem = environments.find { it.id == profile.environmentId }
        renderer = object : DefaultListCellRenderer() {
            override fun getListCellRendererComponent(
                list: JList<*>, value: Any?, index: Int, sel: Boolean, focus: Boolean
            ): Component {
                val c = super.getListCellRendererComponent(list, value, index, sel, focus) as JLabel
                if (value is Environment) {
                    c.icon = ColorDotIcon(value.awtColor())
                    c.text = value.name
                } else {
                    c.icon = null
                    c.text = "— None —"
                }
                return c
            }
        }
    }

    private val permissionsField = JBTextField(profile.deployFilePermissions, 6)
    private val stopCmdField     = JBTextField(profile.stopCommand, 45)
    private val startCmdField    = JBTextField(profile.startCommand, 45)
    private val stopTimeoutField = JBTextField(profile.stopTimeoutSeconds.toString(), 6)

    private val testButton = JButton("Test Connection")
    private val testResult = JBLabel("")

    init {
        title = if (existingProfile == null) "Add Server" else "Edit Server"

        keyFileField.text = profile.keyFilePath
        keyFileField.addBrowseFolderListener(
            "Select SSH Private Key", null, null,
            FileChooserDescriptorFactory.createSingleFileDescriptor()
        )
        passphraseField.text = profile.keyPassphrase
        passwordField.text   = profile.password

        // Key file panel
        val keyPanel = JPanel(GridBagLayout()).apply {
            val gbc = gbc()
            add(JBLabel("Key file:"), gbc.label())
            add(keyFileField, gbc.field(true))
            add(JBLabel("Passphrase:"), gbc.label())
            add(passphraseField, gbc.field(false))
        }
        // Password panel
        val pwPanel = JPanel(GridBagLayout()).apply {
            val gbc = gbc()
            add(JBLabel("Password:"), gbc.label())
            add(passwordField, gbc.field(false))
        }

        authPanel.add(keyPanel, "KEY_FILE")
        authPanel.add(pwPanel,  "PASSWORD")
        authCards.show(authPanel, profile.authType)

        authKeyRadio.addActionListener  { authCards.show(authPanel, "KEY_FILE") }
        authPassRadio.addActionListener { authCards.show(authPanel, "PASSWORD") }

        testButton.addActionListener { runTestConnection() }

        init()
    }

    override fun createCenterPanel(): JComponent {
        val panel = JPanel(GridBagLayout())
        val gbc = gbc()

        fun section(title: String) =
            JBLabel("<html><b>$title</b></html>").also { it.border = BorderFactory.createEmptyBorder(8, 0, 2, 0) }

        // Connection
        panel.add(section("Connection"),  gbc.span())
        panel.add(JBLabel("Name:"),       gbc.label()); panel.add(nameField, gbc.field(false))
        panel.add(JBLabel("Host:"),       gbc.label()); panel.add(hostField, gbc.field(false))
        panel.add(JBLabel("Port:"),       gbc.label()); panel.add(portField, gbc.field(false))
        panel.add(JBLabel("Username:"),    gbc.label()); panel.add(userField,  gbc.field(false))
        panel.add(JBLabel("Environment:"), gbc.label()); panel.add(envCombo,   gbc.field(false))

        // Authentication
        panel.add(section("Authentication"), gbc.span())
        panel.add(JBLabel("Auth type:"),   gbc.label())
        val radioRow = JPanel(FlowLayout(FlowLayout.LEFT, 0, 0)).apply {
            add(authKeyRadio); add(Box.createHorizontalStrut(12)); add(authPassRadio)
        }
        panel.add(radioRow, gbc.field(false))
        panel.add(JPanel(), gbc.label())
        panel.add(authPanel, gbc.field(false))

        // Remote paths
        panel.add(section("Remote Paths"), gbc.span())
        panel.add(JBLabel("Deploy folder:"),  gbc.label()); panel.add(deployPathField, gbc.field(true))
        panel.add(JBLabel("Tomcat folder:"),  gbc.label()); panel.add(tomcatPathField, gbc.field(true))
        panel.add(JBLabel("OSGi state:"),     gbc.label()); panel.add(osgiStateField,  gbc.field(true))
        panel.add(JBLabel("OSGi tmp:"),       gbc.label()); panel.add(osgiTmpField,    gbc.field(true))
        panel.add(JBLabel("Log file:"),       gbc.label())
        val logHint = JPanel(BorderLayout()).apply {
            add(logFileField, BorderLayout.CENTER)
            add(JBLabel(" (empty = catalina.out)").also { it.foreground = Color.GRAY }, BorderLayout.EAST)
        }
        panel.add(logHint, gbc.field(true))

        // Commands
        panel.add(section("Commands (optional, override defaults)"), gbc.span())
        panel.add(JBLabel("Stop command:"),    gbc.label()); panel.add(stopCmdField,  gbc.field(true))
        panel.add(JBLabel("Start command:"),   gbc.label()); panel.add(startCmdField, gbc.field(true))
        panel.add(JBLabel("Stop timeout (s):"),gbc.label()); panel.add(stopTimeoutField, gbc.field(false))
        panel.add(JBLabel("File permissions:"), gbc.label())
        val permRow = JPanel(FlowLayout(FlowLayout.LEFT, 0, 0)).apply {
            add(permissionsField)
            add(Box.createHorizontalStrut(8))
            add(JBLabel("chmod octal (e.g. 644, 664, 755)").also { it.foreground = Color.GRAY })
        }
        panel.add(permRow, gbc.field(false))

        // Test connection
        panel.add(section("Verify"), gbc.span())
        val testRow = JPanel(FlowLayout(FlowLayout.LEFT, 0, 0)).apply {
            add(testButton)
            add(Box.createHorizontalStrut(10))
            add(testResult)
        }
        panel.add(JPanel(), gbc.label())
        panel.add(testRow, gbc.field(false))

        // push everything up
        val filler = GridBagConstraints().apply {
            gridwidth = 2; weightx = 1.0; weighty = 1.0
            fill = GridBagConstraints.BOTH
            gridx = 0; gridy = GridBagConstraints.RELATIVE
        }
        panel.add(JPanel(), filler)

        return JScrollPane(panel).apply {
            preferredSize = Dimension(620, 550)
            border = BorderFactory.createEmptyBorder()
        }
    }

    override fun doValidate(): ValidationInfo? {
        if (nameField.text.isBlank()) return ValidationInfo("Name is required", nameField)
        if (hostField.text.isBlank()) return ValidationInfo("Host is required", hostField)
        val portNum = portField.text.toIntOrNull()
        if (portNum == null || portNum !in 1..65535) return ValidationInfo("Invalid port", portField)
        if (authKeyRadio.isSelected && keyFileField.text.isBlank())
            return ValidationInfo("Key file path is required", keyFileField.textField)
        if (authPassRadio.isSelected && String(passwordField.password).isBlank())
            return ValidationInfo("Password is required", passwordField)
        return null
    }

    fun getProfile(): ServerProfile {
        profile.name            = nameField.text.trim()
        profile.host            = hostField.text.trim()
        profile.port            = portField.text.toIntOrNull() ?: 22
        profile.username        = userField.text.trim()
        profile.authType        = if (authKeyRadio.isSelected) "KEY_FILE" else "PASSWORD"
        profile.keyFilePath     = keyFileField.text.trim()
        profile.keyPassphrase   = String(passphraseField.password)
        profile.password        = String(passwordField.password)
        profile.remoteDeployPath = deployPathField.text.trim()
        profile.remoteTomcatPath = tomcatPathField.text.trim()
        profile.osgiStatePath   = osgiStateField.text.trim()
        profile.osgiTmpPath     = osgiTmpField.text.trim()
        profile.logFilePath           = logFileField.text.trim()
        profile.environmentId         = (envCombo.selectedItem as? Environment)?.id ?: ""
        profile.deployFilePermissions = permissionsField.text.trim().ifEmpty { "644" }
        profile.stopCommand     = stopCmdField.text.trim()
        profile.startCommand    = startCmdField.text.trim()
        profile.stopTimeoutSeconds = stopTimeoutField.text.toIntOrNull() ?: 60
        return profile
    }

    private fun runTestConnection() {
        testButton.isEnabled = false
        testResult.text = "Connecting..."
        testResult.foreground = Color.GRAY

        val tempProfile = getProfile()
        Thread({
            val error = SshService(tempProfile).testConnection()
            SwingUtilities.invokeLater {
                testButton.isEnabled = true
                if (error == null) {
                    testResult.text = "✓ Connection successful"
                    testResult.foreground = Color(0, 150, 0)
                } else {
                    testResult.text = "✗ $error"
                    testResult.foreground = Color.RED
                }
            }
        }, "ssh-test").also { it.isDaemon = true }.start()
    }
}

// ----- layout helpers -----

private class GBCHelper {
    private var row = 0
    private var isFirst = true

    fun label(): GridBagConstraints = GridBagConstraints().apply {
        gridx = 0; gridy = row
        anchor = GridBagConstraints.WEST
        insets = Insets(3, 4, 3, 8)
    }

    fun field(hfill: Boolean): GridBagConstraints = GridBagConstraints().apply {
        gridx = 1; gridy = row++
        anchor = GridBagConstraints.WEST
        if (hfill) { fill = GridBagConstraints.HORIZONTAL; weightx = 1.0 }
        insets = Insets(3, 0, 3, 4)
    }

    fun span(): GridBagConstraints = GridBagConstraints().apply {
        gridx = 0; gridy = row++
        gridwidth = 2
        anchor = GridBagConstraints.WEST
        fill = GridBagConstraints.HORIZONTAL
        weightx = 1.0
        insets = Insets(4, 4, 0, 4)
    }
}

private fun gbc() = GBCHelper()
