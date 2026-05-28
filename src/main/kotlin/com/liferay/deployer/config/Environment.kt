package com.liferay.deployer.config

import com.intellij.util.xmlb.annotations.Tag
import java.awt.Color

@Tag("Environment")
class Environment {
    var id: String       = java.util.UUID.randomUUID().toString()
    var name: String     = "New Environment"
    var colorHex: String = "#4A90D9"

    fun awtColor(): Color = try { Color.decode(colorHex) } catch (_: Exception) { Color(74, 144, 217) }

    fun deepCopy(): Environment {
        val c = Environment()
        c.id       = id
        c.name     = name
        c.colorHex = colorHex
        return c
    }

    override fun toString() = name
}
