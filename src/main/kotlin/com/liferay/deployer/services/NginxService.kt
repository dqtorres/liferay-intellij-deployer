package com.liferay.deployer.services

import com.jcraft.jsch.ChannelExec
import com.jcraft.jsch.ChannelSftp
import com.jcraft.jsch.JSch
import com.jcraft.jsch.Session
import com.liferay.deployer.config.NginxServer
import com.liferay.deployer.config.NginxUpstreamEntry

enum class NodeStatus { ENABLED, DISABLED, UNKNOWN }

class NginxService(private val server: NginxServer) {

    private val jsch = JSch()
    private var session: Session? = null

    @Throws(Exception::class)
    fun connect() {
        if (server.authType == "KEY_FILE") {
            if (server.keyPassphrase.isNotEmpty()) jsch.addIdentity(server.keyFilePath, server.keyPassphrase)
            else jsch.addIdentity(server.keyFilePath)
        }
        val s = jsch.getSession(server.username, server.host, server.port)
        if (server.authType == "PASSWORD") s.setPassword(server.password)
        s.setConfig("StrictHostKeyChecking", "no")
        s.connect(20_000)
        session = s
    }

    fun disconnect() {
        try { session?.disconnect() } catch (_: Exception) {}
        session = null
    }

    /** Reads the nginx config file content via SFTP. */
    private fun readConfig(): String {
        val sftp = session!!.openChannel("sftp") as ChannelSftp
        sftp.connect(10_000)
        return try {
            sftp.get(server.configFilePath).bufferedReader().readText()
        } finally {
            sftp.disconnect()
        }
    }

    /** Writes the nginx config file content via SFTP. */
    private fun writeConfig(content: String) {
        val sftp = session!!.openChannel("sftp") as ChannelSftp
        sftp.connect(10_000)
        try {
            val bytes = content.toByteArray(Charsets.UTF_8)
            sftp.put(bytes.inputStream(), server.configFilePath, ChannelSftp.OVERWRITE)
        } finally {
            sftp.disconnect()
        }
    }

    private fun execCommand(cmd: String): String {
        val channel = session!!.openChannel("exec") as ChannelExec
        channel.setCommand(cmd)
        val out = channel.inputStream
        val err = channel.errStream
        channel.connect(10_000)
        val buf = ByteArray(4096)
        val sb  = StringBuilder()
        while (true) {
            while (out.available() > 0) { val n = out.read(buf); if (n > 0) sb.append(String(buf, 0, n)) }
            while (err.available() > 0) { val n = err.read(buf); if (n > 0) sb.append(String(buf, 0, n)) }
            if (channel.isClosed) break
            Thread.sleep(80)
        }
        channel.disconnect()
        return sb.toString().trim()
    }

    /**
     * Returns the current state of each node by scanning the config file.
     * A node is ENABLED if its serverLine appears uncommented; DISABLED if commented.
     */
    fun getNodeStatuses(): Map<String, NodeStatus> {
        val content = readConfig()
        val lines   = content.lines().map { it.trim() }
        return server.nodes.associate { entry ->
            val active   = lines.any { it == entry.serverLine }
            val disabled = lines.any { isCommentedOut(it, entry.serverLine) }
            entry.id to when {
                active   -> NodeStatus.ENABLED
                disabled -> NodeStatus.DISABLED
                else     -> NodeStatus.UNKNOWN
            }
        }
    }

    /**
     * Enables or disables a node by commenting/uncommenting its server line in the config,
     * then reloads nginx. Returns the reload command output.
     */
    fun setNodeEnabled(entry: NginxUpstreamEntry, enable: Boolean, log: (String) -> Unit): String {
        log("Reading ${server.configFilePath}...")
        val original = readConfig()
        val modified = toggleLine(original, entry.serverLine, enable)

        if (modified == original) {
            val msg = if (enable) "Node already enabled" else "Node already disabled"
            log("  ⚠ $msg (no change in config)")
            return msg
        }

        log("Writing modified config...")
        writeConfig(modified)

        log("Reloading nginx: ${server.reloadCommand}")
        val result = execCommand(server.reloadCommand)
        log(if (result.isBlank()) "  ✓ nginx reloaded" else "  $result")
        return result
    }

    /**
     * Toggles the target line:
     * - enable=true  → removes leading '#' from the commented line
     * - enable=false → adds '# ' before the active line
     *
     * Matches are done on the trimmed content so indentation is preserved.
     */
    private fun toggleLine(config: String, serverLine: String, enable: Boolean): String =
        config.lines().joinToString("\n") { line ->
            val trimmed = line.trim()
            if (enable) {
                if (isCommentedOut(trimmed, serverLine)) {
                    // Restore original indentation, remove the comment prefix
                    val indent = line.takeWhile { it.isWhitespace() }
                    "$indent$serverLine"
                } else line
            } else {
                if (trimmed == serverLine) {
                    val indent = line.takeWhile { it.isWhitespace() }
                    "${indent}# $serverLine  # [disabled by Liferay Deployer]"
                } else line
            }
        }

    /**
     * Returns true if `line` is a commented-out version of `serverLine`.
     * Handles "# server ..." and "# server ... # [disabled by Liferay Deployer]".
     */
    private fun isCommentedOut(line: String, serverLine: String): Boolean {
        if (!line.startsWith("#")) return false
        val stripped = line.removePrefix("#").trim().removeSuffix("[disabled by Liferay Deployer]").trim()
        return stripped == serverLine
    }

    /** Quick SSH ping — returns null on success, error message on failure. */
    fun testConnection(): String? = try {
        connect()
        execCommand("nginx -t 2>&1 | tail -1")
        disconnect()
        null
    } catch (e: Exception) {
        try { disconnect() } catch (_: Exception) {}
        e.message ?: "Unknown error"
    }
}
