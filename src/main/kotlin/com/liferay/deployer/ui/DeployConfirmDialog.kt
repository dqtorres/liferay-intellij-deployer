 package com.liferay.deployer.ui

import com.intellij.openapi.ui.DialogWrapper
import com.liferay.deployer.config.ColorDotIcon
import com.liferay.deployer.config.Environment
import com.liferay.deployer.config.ServerProfile
import com.liferay.deployer.services.DeployStrategy
import java.awt.*
import java.io.File
import javax.swing.*

class DeployConfirmDialog(
    private val servers: List<ServerProfile>,
    private val environments: List<Environment>,
    private val artifacts: List<File>,
    private val strategy: DeployStrategy
) : DialogWrapper(true) {

    init {
        title = "Confirm Deployment"
        setOKButtonText("Deploy")
        init()
    }

    override fun createCenterPanel(): JComponent {
        val root = JPanel()
        root.layout = BoxLayout(root, BoxLayout.Y_AXIS)
        root.border = BorderFactory.createEmptyBorder(4, 4, 4, 4)

        root.add(sectionLabel("Deploying to ${servers.size} server(s) — ${strategyLabel()}"))
        root.add(Box.createVerticalStrut(8))

        servers.forEach { server ->
            val env = environments.find { it.id == server.environmentId }
            root.add(serverRow(server, env))
            root.add(Box.createVerticalStrut(4))
        }

        root.add(Box.createVerticalStrut(12))
        root.add(sectionLabel("Artifacts (${artifacts.size})"))
        root.add(Box.createVerticalStrut(6))

        artifacts.forEach { file ->
            val sizeKb = file.length() / 1024
            val size   = if (sizeKb >= 1024) "%.1f MB".format(sizeKb / 1024.0) else "$sizeKb KB"
            root.add(artifactRow(file.name, size))
            root.add(Box.createVerticalStrut(2))
        }

        return root
    }

    private fun serverRow(server: ServerProfile, env: Environment?): JPanel {
        val row = JPanel(FlowLayout(FlowLayout.LEFT, 6, 0))
        row.isOpaque = false
        row.alignmentX = Component.LEFT_ALIGNMENT

        if (env != null) {
            val dot = JLabel(ColorDotIcon(env.awtColor(), 14) as Icon)
            row.add(dot)
        }

        val envText = if (env != null)
            "<font color='${env.colorHex}'><b>[${env.name}]</b></font> "
        else ""
        val label = JLabel("<html>${envText}<b>${server.name}</b> — ${server.username}@${server.host}:${server.port}</html>")
        label.font = label.font.deriveFont(13f)
        row.add(label)

        return row
    }

    private fun artifactRow(name: String, size: String): JPanel {
        val row = JPanel(FlowLayout(FlowLayout.LEFT, 6, 0))
        row.isOpaque = false
        row.alignmentX = Component.LEFT_ALIGNMENT
        val label = JLabel("<html><tt>$name</tt>  <font color='gray'>$size</font></html>")
        label.font = label.font.deriveFont(12f)
        row.add(label)
        return row
    }

    private fun sectionLabel(text: String): JLabel {
        val lbl = JLabel("<html><b>$text</b></html>")
        lbl.alignmentX = Component.LEFT_ALIGNMENT
        lbl.font = lbl.font.deriveFont(13f)
        return lbl
    }

    private fun strategyLabel() = when (strategy) {
        DeployStrategy.SEQUENTIAL -> "sequential"
        DeployStrategy.PARALLEL   -> "parallel"
    }
}