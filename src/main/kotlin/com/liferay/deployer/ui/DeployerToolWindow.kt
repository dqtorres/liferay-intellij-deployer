package com.liferay.deployer.ui

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.fileChooser.FileChooserDescriptorFactory
import com.intellij.openapi.ui.Messages
import com.intellij.openapi.ui.TextFieldWithBrowseButton
import com.intellij.ui.CheckBoxList
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.components.JBTabbedPane
import com.liferay.deployer.config.ColorDotIcon
import com.liferay.deployer.config.DeployerSettings
import com.liferay.deployer.config.Environment
import com.liferay.deployer.config.ServerProfile
import com.liferay.deployer.services.DeployPhase
import com.liferay.deployer.services.DeployService
import com.liferay.deployer.services.DeployStrategy
import com.liferay.deployer.services.SshService
import java.awt.*
import java.io.File
import java.time.LocalTime
import java.time.format.DateTimeFormatter
import java.util.concurrent.atomic.AtomicBoolean
import javax.swing.*
import javax.swing.event.DocumentEvent
import javax.swing.event.DocumentListener

class DeployerToolWindow {

    private val settings      = DeployerSettings.getInstance()
    private val deployService = DeployService()
    private val nginxPanel    = NginxPanel()

    // ── File selector ──────────────────────────────────────────────────────
    private val sourceFolderField = TextFieldWithBrowseButton()
    private val fileCheckList     = CheckBoxList<File>()
    private val fileCountLabel    = JLabel("No folder selected")

    // ── Server selector ────────────────────────────────────────────────────
    private val serverCheckList   = CheckBoxList<ServerProfile>()
    private val envButtonsPanel   = JPanel(FlowLayout(FlowLayout.LEFT, 4, 2))

    // ── Log area ───────────────────────────────────────────────────────────
    private val logTabs    = JBTabbedPane()
    private val logAreas   = mutableMapOf<String, JTextArea>()
    private val statusLabels = mutableMapOf<String, JLabel>()

    // ── Toolbar controls ───────────────────────────────────────────────────
    private val strategyCombo  = JComboBox(arrayOf("Sequential (Rolling)", "Parallel (All at once)"))
    private val deployButton   = JButton("▶  Deploy")
    private val stopTailButton = JButton("■  Stop Tailing").also { it.isEnabled = false }
    private val clearButton    = JButton("Clear Logs")
    private val refreshButton  = JButton("↻")

    // ── Background tailing ─────────────────────────────────────────────────
    private val tailStopFlags  = mutableMapOf<String, AtomicBoolean>()
    private val tailThreads    = mutableMapOf<String, Thread>()
    private val serverTabIndex = mutableMapOf<String, Int>()

    private val timeFmt = DateTimeFormatter.ofPattern("HH:mm:ss")

    fun getContent(): JComponent {
        sourceFolderField.text = settings.defaultSourceFolder
        sourceFolderField.addBrowseFolderListener(
            "Select Artifacts Folder", null, null,
            FileChooserDescriptorFactory.createSingleFolderDescriptor()
        )
        sourceFolderField.textField.document.addDocumentListener(object : DocumentListener {
            override fun insertUpdate(e: DocumentEvent?)  = scheduleFileRefresh()
            override fun removeUpdate(e: DocumentEvent?)  = scheduleFileRefresh()
            override fun changedUpdate(e: DocumentEvent?) = scheduleFileRefresh()
        })

        strategyCombo.selectedIndex =
            if (settings.defaultStrategy == "PARALLEL") 1 else 0

        deployButton.addActionListener   { deploy() }
        stopTailButton.addActionListener { stopAllTailing() }
        clearButton.addActionListener    { clearLogs() }
        refreshButton.toolTipText = "Refresh server list"
        refreshButton.addActionListener  { refreshServers(); refreshFileList(); nginxPanel.rebuild() }

        serverCheckList.addMouseListener(object : java.awt.event.MouseAdapter() {
            override fun mousePressed(e: java.awt.event.MouseEvent)  = maybeShowContextMenu(e)
            override fun mouseReleased(e: java.awt.event.MouseEvent) = maybeShowContextMenu(e)
            private fun maybeShowContextMenu(e: java.awt.event.MouseEvent) {
                if (!e.isPopupTrigger) return
                val idx = serverCheckList.locationToIndex(e.point)
                if (idx < 0) return
                val server = serverCheckList.getItemAt(idx) ?: return
                serverCheckList.selectedIndex = idx
                buildServerContextMenu(server).show(serverCheckList, e.x, e.y)
            }
        })

        refreshServers()
        refreshFileList()
        return buildUI()
    }

    // ──────────────────────────────────────────────────────────────────────
    //  UI construction
    // ──────────────────────────────────────────────────────────────────────

    private fun buildUI(): JComponent {
        val deployTab = buildDeployTab()
        val nginxTab  = nginxPanel.getContent()

        return JBTabbedPane().apply {
            addTab("Liferay", deployTab)
            addTab("NGINX",   nginxTab)
        }
    }

    private fun buildDeployTab(): JComponent {
        val toolbar = JPanel(FlowLayout(FlowLayout.LEFT, 6, 4)).apply {
            border = BorderFactory.createMatteBorder(0, 0, 1, 0, Color.LIGHT_GRAY)
            add(JLabel("Folder:"))
            add(sourceFolderField.also { it.preferredSize = Dimension(260, it.preferredSize.height) })
            add(JLabel("Strategy:"))
            add(strategyCombo)
            add(Box.createHorizontalStrut(4))
            add(deployButton)
            add(stopTailButton)
            add(clearButton)
            add(refreshButton)
        }

        val leftPanel = buildLeftPanel()

        val logPanel = JPanel(BorderLayout()).apply {
            border = BorderFactory.createTitledBorder("Logs")
            add(logTabs, BorderLayout.CENTER)
        }

        val splitter = JSplitPane(JSplitPane.HORIZONTAL_SPLIT, leftPanel, logPanel).apply {
            dividerLocation = 260
            resizeWeight    = 0.0
            border          = BorderFactory.createEmptyBorder()
        }

        return JPanel(BorderLayout()).apply {
            add(toolbar,  BorderLayout.NORTH)
            add(splitter, BorderLayout.CENTER)
        }
    }

    private fun buildLeftPanel(): JComponent {
        // ── Artifacts section ──────────────────────────────────────────────
        val allBtn  = JButton("All").also { btn ->
            btn.font       = btn.font.deriveFont(11f)
            btn.margin     = Insets(1, 6, 1, 6)
            btn.addActionListener {
                (0 until fileCheckList.model.size).forEach { idx ->
                    fileCheckList.getItemAt(idx)?.let { fileCheckList.setItemSelected(it, true) }
                }
                updateFileCount()
            }
        }
        val noneBtn = JButton("None").also { btn ->
            btn.font       = btn.font.deriveFont(11f)
            btn.margin     = Insets(1, 6, 1, 6)
            btn.addActionListener {
                (0 until fileCheckList.model.size).forEach { idx ->
                    fileCheckList.getItemAt(idx)?.let { fileCheckList.setItemSelected(it, false) }
                }
                updateFileCount()
            }
        }

        fileCheckList.addMouseListener(object : java.awt.event.MouseAdapter() {
            override fun mouseClicked(e: java.awt.event.MouseEvent?) = updateFileCount()
        })

        val artifactHeader = JPanel(FlowLayout(FlowLayout.LEFT, 4, 2)).apply {
            add(JLabel("Artifacts").also { it.font = it.font.deriveFont(Font.BOLD, 11f) })
            add(allBtn)
            add(noneBtn)
            add(fileCountLabel.also { it.font = it.font.deriveFont(10f); it.foreground = Color.GRAY })
        }
        val artifactPanel = JPanel(BorderLayout()).apply {
            border = BorderFactory.createMatteBorder(0, 0, 1, 0, Color.LIGHT_GRAY)
            add(artifactHeader, BorderLayout.NORTH)
            add(JBScrollPane(fileCheckList), BorderLayout.CENTER)
        }

        // ── Servers section ────────────────────────────────────────────────
        val serverHeader = JPanel(BorderLayout()).apply {
            add(JPanel(FlowLayout(FlowLayout.LEFT, 4, 2)).apply {
                add(JLabel("Liferay Servers").also { it.font = it.font.deriveFont(Font.BOLD, 11f) })
            }, BorderLayout.WEST)
            add(envButtonsPanel, BorderLayout.CENTER)
        }
        val serverPanel = JPanel(BorderLayout()).apply {
            add(serverHeader, BorderLayout.NORTH)
            add(JBScrollPane(serverCheckList), BorderLayout.CENTER)
        }

        // ── Vertical split ─────────────────────────────────────────────────
        return JPanel(BorderLayout()).apply {
            preferredSize = Dimension(260, 0)
            border = BorderFactory.createEmptyBorder(2, 2, 2, 2)
            add(JSplitPane(JSplitPane.VERTICAL_SPLIT, artifactPanel, serverPanel).apply {
                resizeWeight    = 0.45
                dividerSize     = 5
                border          = BorderFactory.createEmptyBorder()
            }, BorderLayout.CENTER)
        }
    }

    // ──────────────────────────────────────────────────────────────────────
    //  File list
    // ──────────────────────────────────────────────────────────────────────

    private var refreshTimer: Timer? = null

    private fun scheduleFileRefresh() {
        refreshTimer?.stop()
        refreshTimer = Timer(400) { refreshFileList() }.also {
            it.isRepeats = false
            it.start()
        }
    }

    private fun refreshFileList() {
        fileCheckList.clear()
        val folder = File(sourceFolderField.text.trim())
        if (!folder.isDirectory) {
            fileCountLabel.text = "invalid folder"
            return
        }
        val files = folder.listFiles { f -> f.extension.lowercase() in listOf("jar", "war") }
            ?.sortedBy { it.name } ?: emptyList()

        files.forEach { file ->
            val sizeKb  = file.length() / 1024
            val size    = if (sizeKb >= 1024) "%.1f MB".format(sizeKb / 1024.0) else "$sizeKb KB"
            val date    = java.time.Instant.ofEpochMilli(file.lastModified())
                .atZone(java.time.ZoneId.systemDefault())
                .format(java.time.format.DateTimeFormatter.ofPattern("dd/MM HH:mm"))
            fileCheckList.addItem(file, "${file.name}  ($size · $date)", true)
        }
        updateFileCount()
    }

    private fun updateFileCount() {
        val total   = fileCheckList.model.size
        val checked = (0 until total).count { fileCheckList.isItemSelected(it) }
        fileCountLabel.text = if (total == 0) "0 files" else "$checked / $total"
    }

    private fun selectedFiles(): List<File> =
        (0 until fileCheckList.model.size)
            .filter  { idx -> fileCheckList.isItemSelected(idx) }
            .mapNotNull { idx -> fileCheckList.getItemAt(idx) }

    // ──────────────────────────────────────────────────────────────────────
    //  Server list
    // ──────────────────────────────────────────────────────────────────────

    private fun refreshServers() {
        serverCheckList.clear()
        logTabs.removeAll()
        logAreas.clear()
        statusLabels.clear()
        serverTabIndex.clear()

        rebuildEnvButtons()

        val savedIds = settings.selectedServerIds
            .split(",").filter { it.isNotEmpty() }.toSet()

        settings.servers.forEachIndexed { index, server ->
            val env     = settings.environments.find { it.id == server.environmentId }
            val envBadge = if (env != null) " [${env.name}]" else ""
            val label   = if (env != null)
                "<html>${server.name} <font color='${env.colorHex}'>$envBadge</font></html>"
            else server.name
            // Restore saved selection; if none saved, only first server is checked
            val checked = if (savedIds.isEmpty()) index == 0 else server.id in savedIds
            serverCheckList.addItem(server, label, checked)

            val area = JTextArea().apply {
                isEditable = false
                font       = Font(Font.MONOSPACED, Font.PLAIN, 12)
                background = Color(30, 30, 30)
                foreground = Color(204, 204, 204)
                margin     = Insets(4, 6, 4, 6)
            }
            logAreas[server.id] = area

            val lbl = JLabel("  ●  Idle").apply {
                foreground = Color.GRAY
                font       = font.deriveFont(Font.PLAIN, 11f)
            }
            statusLabels[server.id] = lbl

            val tabHeader = JPanel(FlowLayout(FlowLayout.LEFT, 4, 0)).apply {
                isOpaque = false
                add(JLabel(server.name))
                add(lbl)
            }
            serverTabIndex[server.id] = logTabs.tabCount
            logTabs.addTab(null, JBScrollPane(area))
            logTabs.setTabComponentAt(logTabs.tabCount - 1, tabHeader)
        }
    }

    private fun rebuildEnvButtons() {
        envButtonsPanel.removeAll()

        // "All" button
        envButtonsPanel.add(JButton("All").apply {
            font   = font.deriveFont(11f)
            margin = Insets(1, 6, 1, 6)
            addActionListener {
                (0 until serverCheckList.model.size).forEach { idx ->
                    serverCheckList.getItemAt(idx)?.let { serverCheckList.setItemSelected(it, true) }
                }
            }
        })

        // One button per environment
        settings.environments.forEach { env ->
            envButtonsPanel.add(JButton(env.name).apply {
                font      = font.deriveFont(11f)
                margin    = Insets(1, 6, 1, 6)
                icon      = ColorDotIcon(env.awtColor(), 10)
                toolTipText = "Select only ${env.name} servers"
                addActionListener {
                    (0 until serverCheckList.model.size).forEach { idx ->
                        val item = serverCheckList.getItemAt(idx) ?: return@forEach
                        serverCheckList.setItemSelected(item, item.environmentId == env.id)
                    }
                }
            })
        }

        envButtonsPanel.revalidate()
        envButtonsPanel.repaint()
    }

    private fun selectedServers(): List<ServerProfile> {
        val checkedIds = (0 until serverCheckList.model.size)
            .filter  { idx -> serverCheckList.isItemSelected(idx) }
            .mapNotNull { idx -> serverCheckList.getItemAt(idx)?.id }
            .toSet()
        return settings.servers.filter { it.id in checkedIds }
    }

    // ──────────────────────────────────────────────────────────────────────
    //  Deploy
    // ──────────────────────────────────────────────────────────────────────

    private fun deploy() {
        // Sync server list, preserving selection
        val prevIds = (0 until serverCheckList.model.size)
            .filter  { idx -> serverCheckList.isItemSelected(idx) }
            .mapNotNull { idx -> serverCheckList.getItemAt(idx)?.id }
            .toSet()
        refreshServers()
        (0 until serverCheckList.model.size).forEach { idx ->
            val item = serverCheckList.getItemAt(idx) ?: return@forEach
            if (item.id !in prevIds) serverCheckList.setItemSelected(item, false)
        }

        val targets   = selectedServers()
        val artifacts = selectedFiles()

        if (targets.isEmpty()) {
            Messages.showWarningDialog("Select at least one server.", "No Servers Selected")
            return
        }
        if (artifacts.isEmpty()) {
            Messages.showWarningDialog(
                "No files selected. Select a folder and choose the artifacts to deploy.",
                "No Artifacts Selected"
            )
            return
        }

        val strategy = if (strategyCombo.selectedIndex == 0) DeployStrategy.SEQUENTIAL else DeployStrategy.PARALLEL

        val confirmed = DeployConfirmDialog(targets, settings.environments, artifacts, strategy).showAndGet()
        if (!confirmed) return

        settings.defaultSourceFolder = sourceFolderField.text.trim()
        settings.selectedServerIds = targets.joinToString(",") { it.id }

        stopAllTailing()
        deployButton.isEnabled = false

        ApplicationManager.getApplication().executeOnPooledThread {
            val results = deployService.deploy(
                artifacts = artifacts,
                servers   = targets,
                strategy  = strategy,
                onLog     = { server, msg -> appendLog(server, msg) },
                onPhase   = { server, phase -> updateStatus(server, phase) }
            )
            ApplicationManager.getApplication().invokeLater {
                deployButton.isEnabled = true
                results.filter { it.success }.forEach { startTailing(it.server) }
                val failed = results.filter { !it.success }
                if (failed.isNotEmpty()) {
                    Messages.showErrorDialog(
                        "Some deployments failed:\n\n" + failed.joinToString("\n") { "• ${it.server.name}: ${it.error}" },
                        "Deployment Errors"
                    )
                }
            }
        }
    }

    // ──────────────────────────────────────────────────────────────────────
    //  Log tailing
    // ──────────────────────────────────────────────────────────────────────

    private fun startTailing(server: ServerProfile) {
        val stopFlag = AtomicBoolean(false)
        tailStopFlags[server.id] = stopFlag
        updateStatus(server, DeployPhase.TAILING)

        Thread({
            val ssh = SshService(server)
            try {
                ssh.connect()
                appendLog(server, "\n━━━ Live log: ${server.resolvedLogFilePath()} ━━━\n")
                ssh.tailLog(server.resolvedLogFilePath(), { appendLog(server, it) }, { stopFlag.get() })
            } catch (e: Exception) {
                appendLog(server, "\n⚠ Log tailing stopped: ${e.message}\n")
            } finally {
                try { ssh.disconnect() } catch (_: Exception) {}
                ApplicationManager.getApplication().invokeLater {
                    if (!stopFlag.get()) updateStatus(server, DeployPhase.DONE)
                }
            }
        }, "tail-${server.id}").also { it.isDaemon = true }.also {
            tailThreads[server.id] = it
            it.start()
        }

        stopTailButton.isEnabled = true
    }

    private fun stopAllTailing() {
        tailStopFlags.values.forEach { it.set(true) }
        tailThreads.values.forEach { it.interrupt() }
        tailStopFlags.clear()
        tailThreads.clear()
        ApplicationManager.getApplication().invokeLater {
            stopTailButton.isEnabled = false
            settings.servers.forEach { s ->
                if (statusLabels[s.id]?.text?.contains("Tailing") == true) updateStatus(s, DeployPhase.IDLE)
            }
        }
    }

    // ──────────────────────────────────────────────────────────────────────
    //  Helpers
    // ──────────────────────────────────────────────────────────────────────

    private fun appendLog(server: ServerProfile, raw: String) {
        val msg = raw.trimEnd('\n', '\r')
        if (msg.isEmpty()) return
        val line = "[${LocalTime.now().format(timeFmt)}] $msg\n"
        ApplicationManager.getApplication().invokeLater {
            logAreas[server.id]?.let { area ->
                area.append(line)
                area.caretPosition = area.document.length
            }
        }
    }

    private fun updateStatus(server: ServerProfile, phase: DeployPhase) {
        ApplicationManager.getApplication().invokeLater {
            val (text, color) = when (phase) {
                DeployPhase.IDLE       -> "●  Idle"       to Color.GRAY
                DeployPhase.CONNECTING -> "●  Connecting" to Color.ORANGE
                DeployPhase.UPLOADING  -> "●  Uploading"  to Color.ORANGE
                DeployPhase.STOPPING   -> "●  Stopping"   to Color(200, 130, 0)
                DeployPhase.CLEANING   -> "●  Cleaning"   to Color.ORANGE
                DeployPhase.STARTING   -> "●  Starting"   to Color(0, 150, 220)
                DeployPhase.TAILING    -> "●  Tailing"    to Color(0, 180, 0)
                DeployPhase.DONE       -> "●  Done"       to Color(0, 150, 0)
                DeployPhase.ERROR      -> "●  Error"      to Color.RED
            }
            statusLabels[server.id]?.let { it.text = "  $text"; it.foreground = color }
            serverCheckList.repaint()
        }
    }

    private fun clearLogs() = logAreas.values.forEach { it.text = "" }

    // ──────────────────────────────────────────────────────────────────────
    //  Server context menu
    // ──────────────────────────────────────────────────────────────────────

    private fun buildServerContextMenu(server: ServerProfile): JPopupMenu {
        val menu = JPopupMenu()
        menu.add(JMenuItem("▶  Start").also  { it.addActionListener { runServerAction(server, "START")   } })
        menu.add(JMenuItem("■  Stop").also   { it.addActionListener { runServerAction(server, "STOP")    } })
        menu.add(JMenuItem("↺  Restart").also { it.addActionListener { runServerAction(server, "RESTART") } })
        menu.addSeparator()
        menu.add(JMenuItem("📋  View Logs (catalina.out)").also {
            it.addActionListener { runServerAction(server, "LOGS") }
        })
        return menu
    }

    private fun runServerAction(server: ServerProfile, action: String) {
        // Switch to this server's log tab in the right panel
        serverTabIndex[server.id]?.let { logTabs.selectedIndex = it }

        if (action == "LOGS") {
            if (tailStopFlags.containsKey(server.id)) {
                appendLog(server, "ℹ Already tailing logs for ${server.name}")
            } else {
                val fresh = settings.servers.find { it.id == server.id } ?: return
                startTailing(fresh)
            }
            return
        }

        appendLog(server, "\n━━━ ${action} — ${server.name} ━━━")

        ApplicationManager.getApplication().executeOnPooledThread {
            val fresh = settings.servers.find { it.id == server.id } ?: return@executeOnPooledThread
            val ssh   = SshService(fresh)
            try {
                appendLog(fresh, "Connecting to ${fresh.host}:${fresh.port}…")
                ssh.connect()
                appendLog(fresh, "✓ Connected")

                when (action) {
                    "START" -> {
                        updateStatus(fresh, DeployPhase.STARTING)
                        ssh.startServer { appendLog(fresh, it) }
                        updateStatus(fresh, DeployPhase.IDLE)
                    }
                    "STOP" -> {
                        updateStatus(fresh, DeployPhase.STOPPING)
                        ssh.stopServer { appendLog(fresh, it) }
                        updateStatus(fresh, DeployPhase.IDLE)
                    }
                    "RESTART" -> {
                        updateStatus(fresh, DeployPhase.STOPPING)
                        ssh.stopServer { appendLog(fresh, it) }
                        appendLog(fresh, "✓ Stopped — starting…")
                        updateStatus(fresh, DeployPhase.STARTING)
                        ssh.startServer { appendLog(fresh, it) }
                        updateStatus(fresh, DeployPhase.IDLE)
                    }
                }
                appendLog(fresh, "━━━ Done ━━━\n")
            } catch (e: Exception) {
                appendLog(fresh, "✗ ERROR: ${e.message}")
                updateStatus(fresh, DeployPhase.ERROR)
            } finally {
                ssh.disconnect()
            }
        }
    }
}
