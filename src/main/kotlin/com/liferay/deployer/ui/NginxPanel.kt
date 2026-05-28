package com.liferay.deployer.ui

import com.intellij.ui.components.JBScrollPane
import com.liferay.deployer.config.ColorDotIcon
import com.liferay.deployer.config.DeployerSettings
import com.liferay.deployer.config.NginxServer
import com.liferay.deployer.config.NginxUpstreamEntry
import com.liferay.deployer.services.NginxService
import com.liferay.deployer.services.NodeStatus
import java.awt.*
import javax.swing.*

class NginxPanel {

    private val settings    = DeployerSettings.getInstance()
    private val cardsPanel  = JPanel().apply { layout = BoxLayout(this, BoxLayout.Y_AXIS) }

    // Per-node UI references rebuilt on each rebuild()
    private data class NodeRow(val dot: JLabel, val enableBtn: JButton, val disableBtn: JButton)
    private val nodeRows          = mutableMapOf<String, NodeRow>()       // nodeId → row
    private val serverStatusLabel = mutableMapOf<String, JLabel>()        // serverId → label

    fun getContent(): JComponent {
        val refreshAllBtn = JButton("↻  Refresh All").apply {
            font   = font.deriveFont(11f)
            margin = Insets(2, 8, 2, 8)
            addActionListener { refreshAll() }
        }
        val rebuildBtn = JButton("⟳  Reload Config").apply {
            font        = font.deriveFont(11f)
            margin      = Insets(2, 8, 2, 8)
            toolTipText = "Re-read plugin settings and rebuild this panel"
            addActionListener { rebuild() }
        }
        val toolbar = JPanel(FlowLayout(FlowLayout.LEFT, 6, 4)).apply {
            border = BorderFactory.createMatteBorder(0, 0, 1, 0, Color.LIGHT_GRAY)
            add(refreshAllBtn)
            add(rebuildBtn)
        }

        rebuild()

        return JPanel(BorderLayout()).apply {
            add(toolbar, BorderLayout.NORTH)
            add(JBScrollPane(cardsPanel).apply { border = null }, BorderLayout.CENTER)
        }
    }

    fun rebuild() {
        cardsPanel.removeAll()
        nodeRows.clear()
        serverStatusLabel.clear()

        val servers = settings.nginxServers
        if (servers.isEmpty()) {
            cardsPanel.add(JPanel(FlowLayout(FlowLayout.LEFT, 10, 10)).apply {
                add(JLabel("No NGINX servers configured. Go to Settings → Liferay Remote Deployer to add them.").apply {
                    foreground = Color.GRAY
                })
            })
        } else {
            servers.forEach { server ->
                cardsPanel.add(buildServerCard(server))
                cardsPanel.add(Box.createVerticalStrut(8))
            }
        }
        cardsPanel.add(Box.createVerticalGlue())
        cardsPanel.revalidate()
        cardsPanel.repaint()
    }

    // ──────────────────────────────────────────────────────────────────────
    //  Card per NGINX server
    // ──────────────────────────────────────────────────────────────────────

    private fun buildServerCard(server: NginxServer): JPanel {
        val env = settings.environments.find { it.id == server.environmentId }

        val connStatus = JLabel("●  Not connected").apply {
            foreground  = Color.GRAY
            font        = font.deriveFont(Font.PLAIN, 11f)
        }
        serverStatusLabel[server.id] = connStatus

        val refreshBtn = JButton("Refresh").apply {
            font   = font.deriveFont(11f)
            margin = Insets(2, 6, 2, 6)
            addActionListener { refreshServer(server) }
        }

        // ── Header ─────────────────────────────────────────────────────────
        val header = JPanel(FlowLayout(FlowLayout.LEFT, 6, 4)).apply {
            if (env != null) add(JLabel().also { it.icon = ColorDotIcon(env.awtColor(), 10) })
            add(JLabel(server.name).apply { font = font.deriveFont(Font.BOLD, 12f) })
            add(JLabel("  ${server.username}@${server.host}:${server.port}").apply {
                foreground = Color.GRAY; font = font.deriveFont(11f)
            })
            if (env != null) add(JLabel("[${env.name}]").apply {
                foreground = env.awtColor(); font = font.deriveFont(11f)
            })
            add(Box.createHorizontalStrut(8))
            add(connStatus)
            add(refreshBtn)
        }

        // ── Node rows ──────────────────────────────────────────────────────
        val nodesPanel = JPanel().apply {
            layout = BoxLayout(this, BoxLayout.Y_AXIS)
            border = BorderFactory.createEmptyBorder(2, 12, 4, 4)
        }

        if (server.nodes.isEmpty()) {
            nodesPanel.add(JLabel("  No nodes configured").apply {
                foreground = Color.GRAY; font = font.deriveFont(Font.ITALIC, 11f)
            })
        } else {
            server.nodes.forEach { node -> nodesPanel.add(buildNodeRow(server, node)) }
        }

        return JPanel(BorderLayout()).apply {
            border = BorderFactory.createCompoundBorder(
                BorderFactory.createTitledBorder(""),
                BorderFactory.createEmptyBorder(0, 4, 4, 4)
            )
            maximumSize = Dimension(Int.MAX_VALUE, preferredSize.height + 200)
            add(header,     BorderLayout.NORTH)
            add(nodesPanel, BorderLayout.CENTER)
        }
    }

    private fun buildNodeRow(server: NginxServer, node: NginxUpstreamEntry): JPanel {
        val dot = JLabel("●").apply {
            foreground  = Color.GRAY
            font        = font.deriveFont(14f)
            toolTipText = "Status unknown – click Refresh"
        }
        val enableBtn = JButton("Enable").apply {
            font = font.deriveFont(11f); margin = Insets(1, 8, 1, 8); isEnabled = false
        }
        val disableBtn = JButton("Disable").apply {
            font = font.deriveFont(11f); margin = Insets(1, 8, 1, 8); isEnabled = false
        }
        enableBtn.addActionListener  { toggleNode(server, node, true,  enableBtn, disableBtn) }
        disableBtn.addActionListener { toggleNode(server, node, false, enableBtn, disableBtn) }

        nodeRows[node.id] = NodeRow(dot, enableBtn, disableBtn)

        return JPanel(FlowLayout(FlowLayout.LEFT, 6, 2)).apply {
            add(dot)
            add(JLabel(node.label).apply {
                preferredSize = Dimension(110, preferredSize.height)
                font = font.deriveFont(Font.BOLD, 11f)
            })
            add(JLabel(node.serverLine).apply {
                foreground = Color.GRAY; font = Font(Font.MONOSPACED, Font.PLAIN, 11)
            })
            add(Box.createHorizontalStrut(8))
            add(enableBtn)
            add(disableBtn)
        }
    }

    // ──────────────────────────────────────────────────────────────────────
    //  Status helpers
    // ──────────────────────────────────────────────────────────────────────

    private fun applyNodeStatus(row: NodeRow, status: NodeStatus) {
        when (status) {
            NodeStatus.ENABLED  -> {
                row.dot.foreground  = Color(0, 160, 0)
                row.dot.toolTipText = "Enabled"
                row.enableBtn.isEnabled  = false
                row.disableBtn.isEnabled = true
            }
            NodeStatus.DISABLED -> {
                row.dot.foreground  = Color.RED
                row.dot.toolTipText = "Disabled"
                row.enableBtn.isEnabled  = true
                row.disableBtn.isEnabled = false
            }
            NodeStatus.UNKNOWN  -> {
                row.dot.foreground  = Color.GRAY
                row.dot.toolTipText = "Not found in config"
                row.enableBtn.isEnabled  = false
                row.disableBtn.isEnabled = false
            }
        }
    }

    private fun setServerStatus(server: NginxServer, text: String, color: Color) {
        SwingUtilities.invokeLater {
            serverStatusLabel[server.id]?.let { it.text = text; it.foreground = color }
        }
    }

    // ──────────────────────────────────────────────────────────────────────
    //  Async operations
    // ──────────────────────────────────────────────────────────────────────

    private fun refreshAll() = settings.nginxServers.forEach { refreshServer(it) }

    private fun refreshServer(server: NginxServer) {
        setServerStatus(server, "●  Connecting…", Color.ORANGE)
        server.nodes.forEach { node ->
            nodeRows[node.id]?.let { it.enableBtn.isEnabled = false; it.disableBtn.isEnabled = false }
        }

        Thread({
            val svc = NginxService(server)
            try {
                svc.connect()
                val statuses = svc.getNodeStatuses()
                svc.disconnect()
                SwingUtilities.invokeLater {
                    setServerStatus(server, "●  Connected", Color(0, 150, 0))
                    server.nodes.forEach { node ->
                        val row    = nodeRows[node.id] ?: return@forEach
                        val status = statuses[node.id] ?: NodeStatus.UNKNOWN
                        applyNodeStatus(row, status)
                    }
                }
            } catch (e: Exception) {
                try { svc.disconnect() } catch (_: Exception) {}
                setServerStatus(server, "✗  ${e.message?.take(60) ?: "Error"}", Color.RED)
            }
        }, "nginx-refresh-${server.id}").also { it.isDaemon = true }.start()
    }

    private fun toggleNode(
        server: NginxServer, node: NginxUpstreamEntry,
        enable: Boolean, enableBtn: JButton, disableBtn: JButton
    ) {
        enableBtn.isEnabled  = false
        disableBtn.isEnabled = false
        setServerStatus(server, "●  Working…", Color.ORANGE)

        Thread({
            val svc = NginxService(server)
            try {
                svc.connect()
                svc.setNodeEnabled(node, enable) { _ -> }
                val statuses = svc.getNodeStatuses()
                svc.disconnect()
                SwingUtilities.invokeLater {
                    setServerStatus(server, "●  Connected", Color(0, 150, 0))
                    server.nodes.forEach { n ->
                        val row    = nodeRows[n.id] ?: return@forEach
                        val status = statuses[n.id] ?: NodeStatus.UNKNOWN
                        applyNodeStatus(row, status)
                    }
                }
            } catch (e: Exception) {
                try { svc.disconnect() } catch (_: Exception) {}
                setServerStatus(server, "✗  ${e.message?.take(60) ?: "Error"}", Color.RED)
                SwingUtilities.invokeLater {
                    enableBtn.isEnabled  = !enable
                    disableBtn.isEnabled = enable
                }
            }
        }, "nginx-toggle-${node.id}").also { it.isDaemon = true }.start()
    }
}
