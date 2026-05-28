package com.liferay.deployer.services

import com.liferay.deployer.config.ServerProfile
import java.io.File
import java.util.concurrent.CompletableFuture
import java.util.concurrent.Executors

enum class DeployStrategy { SEQUENTIAL, PARALLEL }

enum class DeployPhase {
    IDLE, CONNECTING, UPLOADING, STOPPING, CLEANING, STARTING, TAILING, DONE, ERROR
}

data class ServerDeployResult(
    val server: ServerProfile,
    val success: Boolean,
    val error: String? = null
)

class DeployService {

    /**
     * Deploys the given artifact files to the given servers.
     * Calls onLog/onPhase from a background thread; the caller must dispatch
     * UI updates to the EDT using invokeLater.
     *
     * Returns after all deployments complete (blocking on the calling thread).
     */
    fun deploy(
        artifacts: List<File>,
        servers: List<ServerProfile>,
        strategy: DeployStrategy,
        onLog: (ServerProfile, String) -> Unit,
        onPhase: (ServerProfile, DeployPhase) -> Unit
    ): List<ServerDeployResult> {
        if (artifacts.isEmpty()) {
            servers.forEach {
                onLog(it, "⚠ No artifacts selected for deployment")
                onPhase(it, DeployPhase.ERROR)
            }
            return servers.map { ServerDeployResult(it, false, "No artifacts selected") }
        }

        val banner = "Found ${artifacts.size} artifact(s): ${artifacts.joinToString { it.name }}"
        servers.forEach { onLog(it, banner) }

        return when (strategy) {
            DeployStrategy.SEQUENTIAL ->
                servers.map { deployToServer(it, artifacts, onLog, onPhase) }

            DeployStrategy.PARALLEL -> {
                val pool = Executors.newFixedThreadPool(servers.size)
                try {
                    servers
                        .map { server ->
                            CompletableFuture.supplyAsync(
                                { deployToServer(server, artifacts, onLog, onPhase) },
                                pool
                            )
                        }
                        .map { it.get() }
                } finally {
                    pool.shutdown()
                }
            }
        }
    }

    private fun deployToServer(
        server: ServerProfile,
        artifacts: List<File>,
        onLog: (ServerProfile, String) -> Unit,
        onPhase: (ServerProfile, DeployPhase) -> Unit
    ): ServerDeployResult {
        val log: (String) -> Unit = { msg -> onLog(server, msg) }
        val phase: (DeployPhase) -> Unit = { p -> onPhase(server, p) }
        val ssh = SshService(server)

        return try {
            log("━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━")
            log("  Connecting to ${server.name} (${server.host}:${server.port})")
            phase(DeployPhase.CONNECTING)
            ssh.connect()
            log("  ✓ Connected\n")

            phase(DeployPhase.UPLOADING)
            log("━━━ Upload (${artifacts.size} file(s) → ${server.remoteDeployPath})")
            artifacts.forEach { ssh.uploadFile(it, server.remoteDeployPath, log) }
            log("  ✓ All artifacts uploaded\n")

            phase(DeployPhase.STOPPING)
            log("━━━ Stopping server [cmd: ${server.resolvedStopCommand()}]")
            val stopCode = ssh.stopServer(log)
            if (stopCode != 0) log("  ⚠ Stop exited with code $stopCode (may be normal if already stopped)")
            else log("  ✓ Stop command exited OK\n")

            phase(DeployPhase.CLEANING)
            log("━━━ Cleaning temporaries")
            log("  osgi/state : ${server.osgiStatePath}")
            log("  osgi/tmp   : ${server.osgiTmpPath}")
            log("  tomcat/work: ${server.resolvedWorkPath()}")
            ssh.cleanTemporaries(log)
            log("  ✓ Cache cleared\n")

            phase(DeployPhase.STARTING)
            log("━━━ Starting server [cmd: ${server.resolvedStartCommand()}]")
            ssh.startServer(log)
            log("  ✓ Start command sent\n")

            phase(DeployPhase.DONE)
            log("  ✓ Deployment complete for ${server.name}")
            log("━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━\n")

            ServerDeployResult(server, true)
        } catch (e: Exception) {
            log("  ✗ ERROR: ${e.message}")
            phase(DeployPhase.ERROR)
            ServerDeployResult(server, false, e.message)
        } finally {
            ssh.disconnect()
        }
    }
}
