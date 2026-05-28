package com.liferay.deployer.config

import com.intellij.openapi.util.JDOMUtil
import com.intellij.util.xmlb.XmlSerializer

object ConfigExportImport {

    fun exportToXml(state: DeployerSettings.State): String {
        val element = XmlSerializer.serialize(state)
        return JDOMUtil.writeElement(element)
    }

    fun importFromXml(xml: String): DeployerSettings.State {
        val element = JDOMUtil.load(xml.reader())
        return XmlSerializer.deserialize(element, DeployerSettings.State::class.java)
    }
}
