package com.liferay.deployer.config

import com.intellij.util.xmlb.annotations.Tag

/**
 * @Tag is required so IntelliJ's XmlSerializer knows the element name when
 * persisting this class inside a MutableList in the State bean.
 * All properties must have defaults for the no-arg constructor the serializer needs.
 */
@Tag("ServerProfile")
class ServerProfile {
    var id: String            = java.util.UUID.randomUUID().toString()
    var name: String          = "New Server"
    var environmentId: String = ""
    var host: String = ""
    var port: Int = 22

    var username: String = "root"

    // AUTH_TYPE: "PASSWORD" or "KEY_FILE"
    var authType: String = "KEY_FILE"
    var password: String = ""
    var keyFilePath: String = ""
    var keyPassphrase: String = ""

    // Remote paths
    var remoteDeployPath: String = "/opt/liferay/deploy"
    var remoteTomcatPath: String = "/opt/liferay/tomcat"
    var osgiStatePath: String = "/opt/liferay/osgi/state"
    var osgiTmpPath: String = "/opt/liferay/osgi/tmp"
    var logFilePath: String = ""       // empty = derive from remoteTomcatPath

    // Octal permission string applied to uploaded files via chmod (e.g. "644", "664", "755")
    var deployFilePermissions: String = "644"

    // Commands — empty means use the catalina.sh defaults
    var stopCommand: String = ""
    var startCommand: String = ""

    // Stop timeout in seconds before force-kill
    var stopTimeoutSeconds: Int = 60

    fun resolvedStopCommand(): String =
        stopCommand.ifEmpty { "$remoteTomcatPath/bin/catalina.sh stop $stopTimeoutSeconds -force" }

    fun resolvedStartCommand(): String =
        startCommand.ifEmpty { "$remoteTomcatPath/bin/catalina.sh start" }

    fun resolvedLogFilePath(): String =
        logFilePath.ifEmpty { "$remoteTomcatPath/logs/catalina.out" }

    fun resolvedWorkPath(): String = "$remoteTomcatPath/work"

    fun deepCopy(): ServerProfile {
        val c = ServerProfile()
        c.id            = id
        c.name          = name
        c.environmentId = environmentId
        c.host = host
        c.port = port
        c.username = username
        c.authType = authType
        c.password = password
        c.keyFilePath = keyFilePath
        c.keyPassphrase = keyPassphrase
        c.remoteDeployPath = remoteDeployPath
        c.remoteTomcatPath = remoteTomcatPath
        c.osgiStatePath = osgiStatePath
        c.osgiTmpPath = osgiTmpPath
        c.logFilePath = logFilePath
        c.deployFilePermissions = deployFilePermissions
        c.stopCommand = stopCommand
        c.startCommand = startCommand
        c.stopTimeoutSeconds = stopTimeoutSeconds
        return c
    }

    override fun toString() = name
}
