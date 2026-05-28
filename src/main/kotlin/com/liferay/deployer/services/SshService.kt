package com.liferay.deployer.services

import com.jcraft.jsch.ChannelExec
import com.jcraft.jsch.ChannelSftp
import com.jcraft.jsch.JSch
import com.jcraft.jsch.Session
import com.jcraft.jsch.SftpProgressMonitor
import com.liferay.deployer.config.ServerProfile
import java.io.File

class SshService(private val profile: ServerProfile) {

    private val jsch = JSch()
    private var session: Session? = null

    @Throws(Exception::class)
    fun connect() {
        if (profile.authType == "KEY_FILE") {
            if (profile.keyPassphrase.isNotEmpty()) {
                jsch.addIdentity(profile.keyFilePath, profile.keyPassphrase)
            } else {
                jsch.addIdentity(profile.keyFilePath)
            }
        }

        val s = jsch.getSession(profile.username, profile.host, profile.port)
        if (profile.authType == "PASSWORD") {
            s.setPassword(profile.password)
        }
        s.setConfig("StrictHostKeyChecking", "no")
        s.connect(30_000)
        session = s
    }

    fun disconnect() {
        try { session?.disconnect() } catch (_: Exception) {}
        session = null
    }

    val isConnected get() = session?.isConnected == true

    @Throws(Exception::class)
    fun uploadFile(localFile: File, remoteDir: String, log: (String) -> Unit) {
        val sftp = session!!.openChannel("sftp") as ChannelSftp
        sftp.connect(10_000)
        try {
            ensureRemoteDir(sftp, remoteDir, log)
            log("  → ${localFile.name} (${localFile.length() / 1024} KB)...")
            val remotePath = "$remoteDir/${localFile.name}"
            sftp.put(
                localFile.absolutePath,
                remotePath,
                object : SftpProgressMonitor {
                    override fun init(op: Int, src: String, dest: String, max: Long) {}
                    override fun count(count: Long) = true
                    override fun end() {}
                },
                ChannelSftp.OVERWRITE
            )
            val octal = profile.deployFilePermissions.toIntOrNull(8) ?: 420 // fallback 644
            sftp.chmod(octal, remotePath)
            log("  ✓ ${localFile.name} uploaded (chmod ${profile.deployFilePermissions})")
        } finally {
            sftp.disconnect()
        }
    }

    private fun ensureRemoteDir(sftp: ChannelSftp, path: String, log: (String) -> Unit) {
        try {
            sftp.stat(path)  // exists → nothing to do
        } catch (_: Exception) {
            log("  Creating remote directory: $path")
            // mkdir -p equivalent: create each segment
            var current = ""
            for (segment in path.split("/").filter { it.isNotEmpty() }) {
                current += "/$segment"
                try { sftp.mkdir(current) } catch (_: Exception) { /* already exists */ }
            }
        }
    }

    @Throws(Exception::class)
    fun executeCommand(command: String, log: (String) -> Unit): Int {
        val channel = session!!.openChannel("exec") as ChannelExec
        channel.setCommand(command)
        val stdout = channel.inputStream
        val stderr = channel.errStream
        channel.connect(10_000)

        val buf = ByteArray(4096)
        while (true) {
            while (stdout.available() > 0) {
                val n = stdout.read(buf); if (n < 0) break
                log(String(buf, 0, n))
            }
            while (stderr.available() > 0) {
                val n = stderr.read(buf); if (n < 0) break
                log("[ERR] " + String(buf, 0, n))
            }
            if (channel.isClosed) break
            Thread.sleep(100)
        }
        // drain any remaining output after close
        var n = stdout.read(buf)
        while (n > 0) { log(String(buf, 0, n)); n = stdout.read(buf) }
        n = stderr.read(buf)
        while (n > 0) { log("[ERR] " + String(buf, 0, n)); n = stderr.read(buf) }

        val exit = channel.exitStatus
        if (exit != 0) log("  [exit code: $exit]")
        channel.disconnect()
        return exit
    }

    /**
     * Tails a remote log file until shouldStop() returns true or the stream ends.
     * Uses `tail -n 100 -f` so the last 100 lines are shown on connect.
     */
    fun tailLog(logPath: String, log: (String) -> Unit, shouldStop: () -> Boolean) {
        val channel = session!!.openChannel("exec") as ChannelExec
        channel.setCommand("tail -n 100 -f \"$logPath\"")
        val stream = channel.inputStream
        channel.connect(10_000)

        val buf = ByteArray(4096)
        try {
            while (!shouldStop() && !channel.isClosed) {
                if (stream.available() > 0) {
                    val n = stream.read(buf)
                    if (n < 0) break
                    log(String(buf, 0, n))
                } else {
                    Thread.sleep(200)
                }
            }
        } finally {
            channel.disconnect()
        }
    }

    fun stopServer(log: (String) -> Unit): Int {
        val cmd = profile.resolvedStopCommand()
        log(">>> STOP: $cmd")
        return executeCommand(cmd, log)
    }

    fun startServer(log: (String) -> Unit): Int {
        val cmd = profile.resolvedStartCommand()
        log(">>> START: $cmd")
        return executeCommand(cmd, log)
    }

    fun cleanTemporaries(log: (String) -> Unit) {
        listOf(
            profile.osgiStatePath to "OSGi state",
            profile.osgiTmpPath   to "OSGi tmp",
            profile.resolvedWorkPath() to "Tomcat work"
        ).forEach { (path, label) ->
            log("  Cleaning $label ($path)...")
            executeCommand("rm -rf \"$path\"/*", log)
            log("  ✓ $label cleaned")
        }
    }

    /** Quick test — returns null on success, error message on failure. */
    fun testConnection(): String? {
        return try {
            connect()
            val result = StringBuilder()
            executeCommand("hostname && echo OK", { result.append(it) })
            disconnect()
            if ("OK" in result) null else "Unexpected response: $result"
        } catch (e: Exception) {
            try { disconnect() } catch (_: Exception) {}
            e.message ?: "Unknown error"
        }
    }
}
