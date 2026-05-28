package com.liferay.deployer.config

import com.intellij.util.xmlb.annotations.Tag
import com.intellij.util.xmlb.annotations.XCollection

@Tag("NginxServer")
class NginxServer {
    var id: String            = java.util.UUID.randomUUID().toString()
    var name: String          = "nginx-1"
    var environmentId: String = ""

    // SSH connection
    var host: String          = ""
    var port: Int             = 22
    var username: String      = "root"
    var authType: String      = "KEY_FILE"   // KEY_FILE | PASSWORD
    var password: String      = ""
    var keyFilePath: String   = ""
    var keyPassphrase: String = ""

    // Nginx config
    var configFilePath: String = "/etc/nginx/conf.d/liferay.conf"
    var reloadCommand: String  = "nginx -s reload"

    @XCollection(style = XCollection.Style.v2)
    var nodes: MutableList<NginxUpstreamEntry> = mutableListOf()

    fun deepCopy(): NginxServer {
        val c = NginxServer()
        c.id            = id
        c.name          = name
        c.environmentId = environmentId
        c.host          = host
        c.port          = port
        c.username      = username
        c.authType      = authType
        c.password      = password
        c.keyFilePath   = keyFilePath
        c.keyPassphrase = keyPassphrase
        c.configFilePath = configFilePath
        c.reloadCommand = reloadCommand
        c.nodes         = nodes.map { it.deepCopy() }.toMutableList()
        return c
    }

    override fun toString() = name
}
