package com.liferay.deployer.config

import com.intellij.util.xmlb.annotations.Tag

@Tag("NginxNode")
class NginxUpstreamEntry {
    var id: String         = java.util.UUID.randomUUID().toString()
    var label: String      = "node-1"
    /** Exact trimmed content of the nginx config line, e.g. "server 10.0.0.1:8080 weight=1;" */
    var serverLine: String = "server 127.0.0.1:8080;"

    fun deepCopy() = NginxUpstreamEntry().also {
        it.id         = id
        it.label      = label
        it.serverLine = serverLine
    }

    override fun toString() = label
}
