package com.liferay.deployer.actions

import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.ui.Messages
import com.intellij.openapi.wm.ToolWindowManager
import com.liferay.deployer.config.DeployerSettings
import com.liferay.deployer.services.DeployService
import com.liferay.deployer.services.DeployStrategy
import java.io.File

/**
 * Keyboard shortcut action (Ctrl+Shift+L) — deploys to all servers that were
 * selected in the tool window using the last-saved defaults.
 * Opens the tool window so the user can watch progress.
 */
class QuickDeployAction : AnAction() {

    override fun actionPerformed(e: AnActionEvent) {
        val project  = e.project ?: return
        val settings = DeployerSettings.getInstance()

        if (settings.servers.isEmpty()) {
            Messages.showInfoMessage(
                "No servers configured. Go to Settings → Tools → Liferay Remote Deployer.",
                "Liferay Remote Deployer"
            )
            return
        }

        val folder = File(settings.defaultSourceFolder)
        if (!folder.isDirectory) {
            Messages.showErrorDialog(
                "Default source folder is not set or doesn't exist.\nConfigure it in the Liferay Deployer tool window.",
                "Liferay Remote Deployer"
            )
            return
        }

        // Reveal the tool window so the user sees log output
        ToolWindowManager.getInstance(project).getToolWindow("Liferay Deployer")?.show()

        val strategy = if (settings.defaultStrategy == "PARALLEL")
            DeployStrategy.PARALLEL else DeployStrategy.SEQUENTIAL

        val artifacts = folder.listFiles { f -> f.extension.lowercase() in listOf("jar", "war") }
            ?.toList() ?: emptyList()

        if (artifacts.isEmpty()) {
            Messages.showWarningDialog("No .jar or .war files found in:\n${folder.absolutePath}", "No Artifacts")
            return
        }

        val service = DeployService()
        ApplicationManager.getApplication().executeOnPooledThread {
            service.deploy(
                artifacts = artifacts,
                servers   = settings.servers,
                strategy  = strategy,
                onLog     = { _, msg -> println(msg) },
                onPhase   = { _, _   -> }
            )
        }
    }

    override fun update(e: AnActionEvent) {
        val settings = DeployerSettings.getInstance()
        e.presentation.isEnabled = settings.servers.isNotEmpty() &&
                settings.defaultSourceFolder.isNotEmpty()
    }
}
