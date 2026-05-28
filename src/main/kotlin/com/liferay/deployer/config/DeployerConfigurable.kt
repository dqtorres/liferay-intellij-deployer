package com.liferay.deployer.config

import com.intellij.icons.AllIcons
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.fileChooser.FileChooserDescriptorFactory
import com.intellij.openapi.options.Configurable
import com.intellij.openapi.ui.Messages
import com.intellij.openapi.ui.TextFieldWithBrowseButton
import com.intellij.ui.AnActionButton
import com.intellij.ui.ColoredListCellRenderer
import com.intellij.ui.SimpleTextAttributes
import com.intellij.ui.ToolbarDecorator
import com.intellij.ui.components.JBList
import com.liferay.deployer.ui.EnvironmentDialog
import com.liferay.deployer.ui.NginxServerDialog
import com.liferay.deployer.ui.ServerProfileDialog
import java.awt.*
import java.io.File
import javax.swing.*
import javax.swing.filechooser.FileNameExtensionFilter

class DeployerConfigurable : Configurable {

    private val settings = DeployerSettings.getInstance()

    // Working copies
    private val workingEnvs     = mutableListOf<Environment>()
    private val envListModel    = DefaultListModel<Environment>()
    private val envJBList       = JBList(envListModel)

    private val workingServers  = mutableListOf<ServerProfile>()
    private val serverListModel = DefaultListModel<ServerProfile>()
    private val serverJBList    = JBList(serverListModel)

    private val workingNginx     = mutableListOf<NginxServer>()
    private val nginxListModel   = DefaultListModel<NginxServer>()
    private val nginxJBList      = JBList(nginxListModel)

    private val sourceFolderField = TextFieldWithBrowseButton()
    private val strategyCombo     = JComboBox(arrayOf("SEQUENTIAL", "PARALLEL"))

    override fun getDisplayName() = "Liferay Remote Deployer"

    override fun createComponent(): JComponent {
        reset()

        sourceFolderField.addBrowseFolderListener(
            "Select Default Source Folder", null, null,
            FileChooserDescriptorFactory.createSingleFolderDescriptor()
        )

        // ── Environment list renderer (colored badge) ──────────────────────
        envJBList.selectionMode = ListSelectionModel.SINGLE_SELECTION
        envJBList.cellRenderer  = object : ColoredListCellRenderer<Environment>() {
            override fun customizeCellRenderer(
                list: JList<out Environment>, value: Environment?, index: Int,
                selected: Boolean, hasFocus: Boolean
            ) {
                if (value == null) return
                icon = ColorDotIcon(value.awtColor())
                append(value.name)
            }
        }

        // ── Server list renderer (name + env badge) ────────────────────────
        serverJBList.selectionMode = ListSelectionModel.SINGLE_SELECTION
        serverJBList.cellRenderer  = object : ColoredListCellRenderer<ServerProfile>() {
            override fun customizeCellRenderer(
                list: JList<out ServerProfile>, value: ServerProfile?, index: Int,
                selected: Boolean, hasFocus: Boolean
            ) {
                if (value == null) return
                val env = workingEnvs.find { it.id == value.environmentId }
                if (env != null) icon = ColorDotIcon(env.awtColor())
                append(value.name)
                if (env != null) {
                    append("  ${env.name}", SimpleTextAttributes.GRAYED_SMALL_ATTRIBUTES)
                }
                append("  ${value.username}@${value.host}:${value.port}",
                    SimpleTextAttributes.GRAYED_ATTRIBUTES)
            }
        }

        val envDecorator = ToolbarDecorator.createDecorator(envJBList)
            .setAddAction { _ ->
                val dialog = EnvironmentDialog(null)
                if (dialog.showAndGet()) { workingEnvs.add(dialog.getEnvironment()); rebuildEnvModel() }
            }
            .setEditAction { _ ->
                val sel = envJBList.selectedValue ?: return@setEditAction
                val dialog = EnvironmentDialog(sel)
                if (dialog.showAndGet()) {
                    val idx = workingEnvs.indexOfFirst { it.id == sel.id }
                    if (idx >= 0) { workingEnvs[idx] = dialog.getEnvironment(); rebuildEnvModel() }
                }
            }
            .setRemoveAction { _ ->
                val sel = envJBList.selectedValue ?: return@setRemoveAction
                workingEnvs.removeIf { it.id == sel.id }
                // Clear env assignment from servers in this environment
                workingServers.filter { it.environmentId == sel.id }
                    .forEach { it.environmentId = "" }
                rebuildEnvModel()
                rebuildServerModel()
            }
            .createPanel().also { it.preferredSize = Dimension(0, 130) }

        val serverDecorator = ToolbarDecorator.createDecorator(serverJBList)
            .setAddAction { _ ->
                val dialog = ServerProfileDialog(null, workingEnvs)
                if (dialog.showAndGet()) { workingServers.add(dialog.getProfile()); rebuildServerModel() }
            }
            .setEditAction { _ ->
                val sel = serverJBList.selectedValue ?: return@setEditAction
                val dialog = ServerProfileDialog(sel, workingEnvs)
                if (dialog.showAndGet()) {
                    val idx = workingServers.indexOfFirst { it.id == sel.id }
                    if (idx >= 0) { workingServers[idx] = dialog.getProfile(); rebuildServerModel() }
                }
            }
            .setRemoveAction { _ ->
                val sel = serverJBList.selectedValue ?: return@setRemoveAction
                workingServers.removeIf { it.id == sel.id }
                rebuildServerModel()
            }
            .addExtraAction(object : AnActionButton("Duplicate", AllIcons.Actions.Copy) {
                override fun actionPerformed(e: AnActionEvent) {
                    val sel = serverJBList.selectedValue ?: return
                    val copy = sel.deepCopy().also {
                        it.id   = java.util.UUID.randomUUID().toString()
                        it.name = "${it.name} (copy)"
                    }
                    workingServers.add(copy); rebuildServerModel()
                }
            })
            .createPanel().also { it.preferredSize = Dimension(0, 180) }

        // ── NGINX server list renderer ─────────────────────────────────────
        nginxJBList.selectionMode = ListSelectionModel.SINGLE_SELECTION
        nginxJBList.cellRenderer  = object : ColoredListCellRenderer<NginxServer>() {
            override fun customizeCellRenderer(
                list: JList<out NginxServer>, value: NginxServer?, index: Int,
                selected: Boolean, hasFocus: Boolean
            ) {
                if (value == null) return
                val env = workingEnvs.find { it.id == value.environmentId }
                if (env != null) icon = ColorDotIcon(env.awtColor())
                append(value.name)
                if (env != null) append("  ${env.name}", SimpleTextAttributes.GRAYED_SMALL_ATTRIBUTES)
                append("  ${value.host}  ${value.nodes.size} nodes", SimpleTextAttributes.GRAYED_ATTRIBUTES)
            }
        }

        val nginxDecorator = ToolbarDecorator.createDecorator(nginxJBList)
            .setAddAction { _ ->
                val dialog = NginxServerDialog(null, workingEnvs)
                if (dialog.showAndGet()) { workingNginx.add(dialog.getServer()); rebuildNginxModel() }
            }
            .setEditAction { _ ->
                val sel = nginxJBList.selectedValue ?: return@setEditAction
                val dialog = NginxServerDialog(sel, workingEnvs)
                if (dialog.showAndGet()) {
                    val idx = workingNginx.indexOfFirst { it.id == sel.id }
                    if (idx >= 0) { workingNginx[idx] = dialog.getServer(); rebuildNginxModel() }
                }
            }
            .setRemoveAction { _ ->
                val sel = nginxJBList.selectedValue ?: return@setRemoveAction
                workingNginx.removeIf { it.id == sel.id }
                rebuildNginxModel()
            }
            .createPanel().also { it.preferredSize = Dimension(0, 150) }

        fun section(t: String) = JLabel("<html><b>$t</b></html>").apply {
            border = BorderFactory.createEmptyBorder(8, 0, 2, 0)
        }

        val panel = JPanel(GridBagLayout())
        val gbc   = GridBagConstraints()

        fun row(comp: JComponent, fill: Boolean = true, weight: Double = 0.0) {
            gbc.apply {
                gridx = 0; gridwidth = 2; weightx = 1.0
                weighty = weight
                this.fill = if (fill) GridBagConstraints.BOTH else GridBagConstraints.HORIZONTAL
                insets    = Insets(2, 0, 2, 0)
            }
            panel.add(comp, gbc)
            gbc.gridy++
        }

        gbc.gridy = 0
        row(section("Environments"))
        row(envDecorator, weight = 0.2)
        row(section("Liferay Servers"))
        row(serverDecorator, weight = 0.4)
        row(section("NGINX Servers"))
        row(nginxDecorator, weight = 0.4)
        row(section("Defaults"))

        // defaults row
        gbc.apply {
            gridwidth = 1; weightx = 0.0; weighty = 0.0
            fill = GridBagConstraints.NONE; insets = Insets(3, 0, 3, 8)
        }
        gbc.gridx = 0; panel.add(JLabel("Default source folder:"), gbc)
        gbc.gridx = 1; gbc.fill = GridBagConstraints.HORIZONTAL; gbc.weightx = 1.0
        panel.add(sourceFolderField, gbc); gbc.gridy++

        gbc.gridx = 0; gbc.fill = GridBagConstraints.NONE; gbc.weightx = 0.0
        panel.add(JLabel("Default strategy:"), gbc)
        gbc.gridx = 1; panel.add(strategyCombo, gbc); gbc.gridy++

        // filler
        gbc.gridx = 0; gbc.gridwidth = 2; gbc.weighty = 0.01
        gbc.fill = GridBagConstraints.BOTH; panel.add(JPanel(), gbc)

        // ── Export / Import ────────────────────────────────────────────────
        val exportBtn = JButton("Export Config…").apply {
            toolTipText = "Save all settings (including credentials) to an XML file"
            addActionListener { doExport() }
        }
        val importBtn = JButton("Import Config…").apply {
            toolTipText = "Load settings from a previously exported XML file"
            addActionListener { doImport() }
        }
        val ioBar = JPanel(FlowLayout(FlowLayout.LEFT, 6, 4)).apply {
            border = BorderFactory.createMatteBorder(1, 0, 0, 0, Color.LIGHT_GRAY)
            add(exportBtn)
            add(importBtn)
            add(JLabel("⚠ Exported file contains credentials — keep it secure.").apply {
                foreground = Color(160, 100, 0); font = font.deriveFont(Font.PLAIN, 11f)
            })
        }

        return JPanel(BorderLayout()).apply {
            add(panel,  BorderLayout.CENTER)
            add(ioBar,  BorderLayout.SOUTH)
            border = BorderFactory.createEmptyBorder(10, 10, 10, 10)
        }
    }

    private fun doExport() {
        val xml = try { ConfigExportImport.exportToXml(settings.state) }
                  catch (e: Exception) { Messages.showErrorDialog("Export failed: ${e.message}", "Export Error"); return }

        val chooser = JFileChooser().apply {
            dialogTitle  = "Export Liferay Deployer Config"
            fileFilter   = FileNameExtensionFilter("XML files (*.xml)", "xml")
            selectedFile = File("liferay-deployer-config.xml")
        }
        if (chooser.showSaveDialog(null) != JFileChooser.APPROVE_OPTION) return
        var file = chooser.selectedFile
        if (!file.name.endsWith(".xml")) file = File(file.absolutePath + ".xml")
        try {
            file.writeText(xml, Charsets.UTF_8)
            Messages.showInfoMessage("Config exported to:\n${file.absolutePath}", "Export Successful")
        } catch (e: Exception) {
            Messages.showErrorDialog("Could not write file: ${e.message}", "Export Error")
        }
    }

    private fun doImport() {
        val chooser = JFileChooser().apply {
            dialogTitle = "Import Liferay Deployer Config"
            fileFilter  = FileNameExtensionFilter("XML files (*.xml)", "xml")
        }
        if (chooser.showOpenDialog(null) != JFileChooser.APPROVE_OPTION) return
        val imported = try { ConfigExportImport.importFromXml(chooser.selectedFile.readText(Charsets.UTF_8)) }
                       catch (e: Exception) { Messages.showErrorDialog("Import failed: ${e.message}", "Import Error"); return }

        workingEnvs.clear();    workingEnvs.addAll(imported.environments)
        workingServers.clear(); workingServers.addAll(imported.servers)
        workingNginx.clear();   workingNginx.addAll(imported.nginxServers)

        rebuildEnvModel(); rebuildServerModel(); rebuildNginxModel()
        sourceFolderField.text     = imported.defaultSourceFolder
        strategyCombo.selectedItem = imported.defaultStrategy

        Messages.showInfoMessage(
            "Imported ${imported.environments.size} environments, " +
            "${imported.servers.size} Liferay servers, ${imported.nginxServers.size} NGINX servers.\n" +
            "Click Apply to save the imported configuration.",
            "Import Successful"
        )
    }

    private fun rebuildEnvModel() {
        envListModel.clear()
        workingEnvs.forEach { envListModel.addElement(it) }
        rebuildServerModel() // refresh server list so env badges update
    }

    private fun rebuildServerModel() {
        serverListModel.clear()
        workingServers.forEach { serverListModel.addElement(it) }
    }

    private fun rebuildNginxModel() {
        nginxListModel.clear()
        workingNginx.forEach { nginxListModel.addElement(it) }
    }

    override fun isModified(): Boolean {
        if (sourceFolderField.text != settings.defaultSourceFolder) return true
        if (strategyCombo.selectedItem as String != settings.defaultStrategy) return true
        if (workingEnvs.size != settings.environments.size) return true
        if (workingServers.size != settings.servers.size) return true
        if (workingNginx.size != settings.nginxServers.size) return true
        return false
    }

    override fun apply() {
        settings.defaultSourceFolder = sourceFolderField.text
        settings.defaultStrategy     = strategyCombo.selectedItem as String
        settings.environments.clear()
        settings.environments.addAll(workingEnvs.map { it.deepCopy() })
        settings.servers.clear()
        settings.servers.addAll(workingServers.map { it.deepCopy() })
        settings.nginxServers.clear()
        settings.nginxServers.addAll(workingNginx.map { it.deepCopy() })
    }

    override fun reset() {
        workingEnvs.clear()
        settings.environments.forEach { workingEnvs.add(it.deepCopy()) }

        workingServers.clear()
        settings.servers.forEach { workingServers.add(it.deepCopy()) }

        workingNginx.clear()
        settings.nginxServers.forEach { workingNginx.add(it.deepCopy()) }

        rebuildEnvModel()
        rebuildNginxModel()

        sourceFolderField.text     = settings.defaultSourceFolder
        strategyCombo.selectedItem = settings.defaultStrategy
    }
}

// ── Small colored circle icon ──────────────────────────────────────────────
class ColorDotIcon(private val color: java.awt.Color, private val size: Int = 12) : javax.swing.Icon {
    override fun paintIcon(c: java.awt.Component?, g: java.awt.Graphics, x: Int, y: Int) {
        val g2 = g.create() as java.awt.Graphics2D
        g2.setRenderingHint(java.awt.RenderingHints.KEY_ANTIALIASING, java.awt.RenderingHints.VALUE_ANTIALIAS_ON)
        g2.color = color
        g2.fillOval(x, y + (getIconHeight() - size) / 2, size, size)
        g2.color = color.darker()
        g2.drawOval(x, y + (getIconHeight() - size) / 2, size - 1, size - 1)
        g2.dispose()
    }
    override fun getIconWidth()  = size + 2
    override fun getIconHeight() = 16
}
