package com.liferay.deployer.ui

import com.intellij.openapi.fileChooser.FileChooserDescriptorFactory
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.openapi.ui.TextFieldWithBrowseButton
import com.intellij.openapi.ui.ValidationInfo
import com.intellij.ui.ColoredListCellRenderer
import com.intellij.ui.SimpleTextAttributes
import com.intellij.ui.ToolbarDecorator
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBList
import com.intellij.ui.components.JBPasswordField
import com.intellij.ui.components.JBTextField
import com.liferay.deployer.config.ColorDotIcon
import com.liferay.deployer.config.Environment
import com.liferay.deployer.config.NginxServer
import com.liferay.deployer.config.NginxUpstreamEntry
import com.liferay.deployer.services.NginxService
import java.awt.*
import javax.swing.*

class NginxServerDialog(
    existing: NginxServer?,
    private val environments: List<Environment> = emptyList()
) : DialogWrapper(true) {

    private val server = existing?.deepCopy() ?: NginxServer()

    private val nameField    = JBTextField(server.name, 28)
    private val hostField    = JBTextField(server.host, 22)
    private val portField    = JBTextField(server.port.toString(), 6)
    private val userField    = JBTextField(server.username, 18)

    private val keyRadio  = JRadioButton("SSH Key File", server.authType == "KEY_FILE")
    private val passRadio = JRadioButton("Password",     server.authType == "PASSWORD")
    private val authGroup = ButtonGroup().also { it.add(keyRadio); it.add(passRadio) }

    private val keyFileField    = TextFieldWithBrowseButton()
    private val passphraseField = JBPasswordField()
    private val passwordField   = JBPasswordField()
    private val authCards       = CardLayout()
    private val authPanel       = JPanel(authCards)

    private val configPathField = JBTextField(server.configFilePath, 40)
    private val reloadCmdField  = JBTextField(server.reloadCommand, 40)

    private val envCombo = JComboBox<Environment?>().apply {
        addItem(null)
        environments.forEach { addItem(it) }
        selectedItem = environments.find { it.id == server.environmentId }
        renderer = object : DefaultListCellRenderer() {
            override fun getListCellRendererComponent(list: JList<*>, v: Any?, i: Int, s: Boolean, f: Boolean): Component {
                val c = super.getListCellRendererComponent(list, v, i, s, f) as JLabel
                if (v is Environment) { c.icon = ColorDotIcon(v.awtColor()); c.text = v.name }
                else { c.icon = null; c.text = "— None —" }
                return c
            }
        }
    }

    // Nodes list
    private val nodeListModel = DefaultListModel<NginxUpstreamEntry>()
    private val nodeJBList    = JBList(nodeListModel)

    private val testButton = JButton("Test Connection")
    private val testResult = JBLabel("")

    init {
        title = if (existing == null) "Add NGINX Server" else "Edit NGINX Server"

        keyFileField.text = server.keyFilePath
        keyFileField.addBrowseFolderListener("Select SSH Key", null, null,
            FileChooserDescriptorFactory.createSingleFileDescriptor())
        passphraseField.text = server.keyPassphrase
        passwordField.text   = server.password

        val keyPanel = JPanel(GridBagLayout()).apply {
            val g = GBC2()
            add(JBLabel("Key file:"),    g.lbl()); add(keyFileField, g.fld(true))
            add(JBLabel("Passphrase:"), g.lbl()); add(passphraseField, g.fld(false))
        }
        val pwPanel = JPanel(GridBagLayout()).apply {
            val g = GBC2()
            add(JBLabel("Password:"), g.lbl()); add(passwordField, g.fld(false))
        }
        authPanel.add(keyPanel, "KEY_FILE"); authPanel.add(pwPanel, "PASSWORD")
        authCards.show(authPanel, server.authType)
        keyRadio.addActionListener  { authCards.show(authPanel, "KEY_FILE") }
        passRadio.addActionListener { authCards.show(authPanel, "PASSWORD") }

        server.nodes.forEach { nodeListModel.addElement(it) }

        nodeJBList.cellRenderer = object : ColoredListCellRenderer<NginxUpstreamEntry>() {
            override fun customizeCellRenderer(list: JList<out NginxUpstreamEntry>, value: NginxUpstreamEntry?,
                index: Int, selected: Boolean, hasFocus: Boolean) {
                if (value == null) return
                append(value.label)
                append("  ${value.serverLine}", SimpleTextAttributes.GRAYED_ATTRIBUTES)
            }
        }

        testButton.addActionListener { runTest() }
        init()
    }

    override fun createCenterPanel(): JComponent {
        val panel = JPanel(GridBagLayout())
        val g = GBC2()

        fun section(t: String) = JBLabel("<html><b>$t</b></html>").apply {
            border = BorderFactory.createEmptyBorder(8, 0, 2, 0)
        }

        panel.add(section("Connection"), g.span())
        panel.add(JBLabel("Name:"),        g.lbl()); panel.add(nameField,  g.fld(false))
        panel.add(JBLabel("Host:"),        g.lbl()); panel.add(hostField,  g.fld(false))
        panel.add(JBLabel("Port:"),        g.lbl()); panel.add(portField,  g.fld(false))
        panel.add(JBLabel("Username:"),    g.lbl()); panel.add(userField,  g.fld(false))
        panel.add(JBLabel("Environment:"), g.lbl()); panel.add(envCombo,   g.fld(false))

        panel.add(section("Authentication"), g.span())
        val radioRow = JPanel(FlowLayout(FlowLayout.LEFT, 0, 0)).apply {
            add(keyRadio); add(Box.createHorizontalStrut(12)); add(passRadio)
        }
        panel.add(JBLabel("Auth type:"), g.lbl()); panel.add(radioRow, g.fld(false))
        panel.add(JPanel(),              g.lbl()); panel.add(authPanel, g.fld(false))

        panel.add(section("Nginx Config"), g.span())
        panel.add(JBLabel("Config file:"),    g.lbl()); panel.add(configPathField, g.fld(true))
        panel.add(JBLabel("Reload command:"), g.lbl()); panel.add(reloadCmdField,  g.fld(true))

        panel.add(section("Upstream Nodes"), g.span())

        val nodesDecorator = ToolbarDecorator.createDecorator(nodeJBList)
            .setAddAction { _ ->
                val d = NginxNodeDialog(null)
                if (d.showAndGet()) { nodeListModel.addElement(d.getEntry()) }
            }
            .setEditAction { _ ->
                val sel = nodeJBList.selectedValue ?: return@setEditAction
                val d   = NginxNodeDialog(sel)
                if (d.showAndGet()) { nodeListModel.set(nodeJBList.selectedIndex, d.getEntry()) }
            }
            .setRemoveAction { _ ->
                val idx = nodeJBList.selectedIndex
                if (idx >= 0) nodeListModel.remove(idx)
            }
            .createPanel().also { it.preferredSize = Dimension(0, 140) }
        panel.add(JPanel(), g.lbl()); panel.add(nodesDecorator, g.fld(true))

        panel.add(section("Verify"), g.span())
        panel.add(JPanel(), g.lbl())
        panel.add(JPanel(FlowLayout(FlowLayout.LEFT, 0, 0)).apply {
            add(testButton); add(Box.createHorizontalStrut(10)); add(testResult)
        }, g.fld(false))

        val filler = GridBagConstraints().apply {
            gridwidth = 2; weightx = 1.0; weighty = 1.0
            fill = GridBagConstraints.BOTH; gridx = 0; gridy = GridBagConstraints.RELATIVE
        }
        panel.add(JPanel(), filler)

        return JScrollPane(panel).apply { preferredSize = Dimension(580, 540); border = null }
    }

    override fun doValidate(): ValidationInfo? {
        if (nameField.text.isBlank()) return ValidationInfo("Name required", nameField)
        if (hostField.text.isBlank()) return ValidationInfo("Host required", hostField)
        if (portField.text.toIntOrNull()?.let { it !in 1..65535 } != false)
            return ValidationInfo("Invalid port", portField)
        return null
    }

    fun getServer(): NginxServer {
        server.name          = nameField.text.trim()
        server.host          = hostField.text.trim()
        server.port          = portField.text.toIntOrNull() ?: 22
        server.username      = userField.text.trim()
        server.authType      = if (keyRadio.isSelected) "KEY_FILE" else "PASSWORD"
        server.keyFilePath   = keyFileField.text.trim()
        server.keyPassphrase = String(passphraseField.password)
        server.password      = String(passwordField.password)
        server.environmentId = (envCombo.selectedItem as? Environment)?.id ?: ""
        server.configFilePath = configPathField.text.trim()
        server.reloadCommand = reloadCmdField.text.trim()
        server.nodes.clear()
        server.nodes.addAll((0 until nodeListModel.size).map { nodeListModel.getElementAt(it) })
        return server
    }

    private fun runTest() {
        testButton.isEnabled = false
        testResult.text = "Connecting…"; testResult.foreground = Color.GRAY
        Thread({
            val err = NginxService(getServer()).testConnection()
            SwingUtilities.invokeLater {
                testButton.isEnabled = true
                if (err == null) { testResult.text = "✓ OK"; testResult.foreground = Color(0, 150, 0) }
                else             { testResult.text = "✗ $err"; testResult.foreground = Color.RED }
            }
        }, "nginx-test").also { it.isDaemon = true }.start()
    }
}

// ── Simple dialog for a single upstream node ──────────────────────────────
class NginxNodeDialog(existing: NginxUpstreamEntry?) : DialogWrapper(true) {
    private val entry       = existing?.deepCopy() ?: NginxUpstreamEntry()
    private val labelField  = JBTextField(entry.label, 22)
    private val lineField   = JBTextField(entry.serverLine, 40)

    init { title = if (existing == null) "Add Node" else "Edit Node"; init() }

    override fun createCenterPanel(): JComponent {
        val panel = JPanel(GridBagLayout())
        val g = GBC2()
        panel.add(JBLabel("Label:"),       g.lbl()); panel.add(labelField, g.fld(true))
        panel.add(JBLabel("Server line:"), g.lbl())
        val withHint = JPanel(BorderLayout()).apply {
            add(lineField, BorderLayout.CENTER)
            add(JBLabel(" (from nginx.conf)").also { it.foreground = Color.GRAY }, BorderLayout.EAST)
        }
        panel.add(withHint, g.fld(true))
        return panel
    }

    override fun doValidate(): ValidationInfo? {
        if (labelField.text.isBlank()) return ValidationInfo("Label required", labelField)
        if (lineField.text.isBlank())  return ValidationInfo("Server line required", lineField)
        return null
    }

    fun getEntry(): NginxUpstreamEntry {
        entry.label      = labelField.text.trim()
        entry.serverLine = lineField.text.trim()
        return entry
    }
}

// ── GridBagConstraints helper ─────────────────────────────────────────────
private class GBC2 {
    private var row = 0
    fun lbl() = GridBagConstraints().apply {
        gridx = 0; gridy = row; anchor = GridBagConstraints.NORTHWEST; insets = Insets(3, 4, 3, 8)
    }
    fun fld(hfill: Boolean) = GridBagConstraints().apply {
        gridx = 1; gridy = row++; anchor = GridBagConstraints.WEST; insets = Insets(3, 0, 3, 4)
        if (hfill) { fill = GridBagConstraints.HORIZONTAL; weightx = 1.0 }
    }
    fun span() = GridBagConstraints().apply {
        gridx = 0; gridy = row++; gridwidth = 2
        anchor = GridBagConstraints.WEST; fill = GridBagConstraints.HORIZONTAL; weightx = 1.0
        insets = Insets(4, 4, 0, 4)
    }
}
