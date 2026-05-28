package com.liferay.deployer.config

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.PersistentStateComponent
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.State
import com.intellij.openapi.components.Storage
import com.intellij.util.xmlb.annotations.XCollection

@Service(Service.Level.APP)
@State(
    name = "LiferayRemoteDeployer",
    storages = [Storage("liferay-remote-deployer.xml")]
)
class DeployerSettings : PersistentStateComponent<DeployerSettings.State> {

    class State {
        @XCollection(style = XCollection.Style.v2)
        var environments: MutableList<Environment> = mutableListOf()

        @XCollection(style = XCollection.Style.v2)
        var servers: MutableList<ServerProfile> = mutableListOf()

        @XCollection(style = XCollection.Style.v2)
        var nginxServers: MutableList<NginxServer> = mutableListOf()

        var defaultSourceFolder: String = ""
        var defaultStrategy: String = "SEQUENTIAL"
        // Comma-separated server IDs that were checked on last deploy/close
        var selectedServerIds: String = ""
    }

    private var myState = State()

    override fun getState(): State = myState

    override fun loadState(state: State) {
        myState = state
    }

    var environments: MutableList<Environment>
        get() = myState.environments
        set(value) { myState.environments = value }

    var servers: MutableList<ServerProfile>
        get() = myState.servers
        set(value) { myState.servers = value }

    var defaultSourceFolder: String
        get() = myState.defaultSourceFolder
        set(value) { myState.defaultSourceFolder = value }

    var nginxServers: MutableList<NginxServer>
        get() = myState.nginxServers
        set(value) { myState.nginxServers = value }

    var defaultStrategy: String
        get() = myState.defaultStrategy
        set(value) { myState.defaultStrategy = value }

    var selectedServerIds: String
        get() = myState.selectedServerIds
        set(value) { myState.selectedServerIds = value }

    companion object {
        fun getInstance(): DeployerSettings =
            ApplicationManager.getApplication().getService(DeployerSettings::class.java)
    }
}
