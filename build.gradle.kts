plugins {
    id("java")
    id("org.jetbrains.kotlin.jvm") version "2.0.21"
    id("org.jetbrains.intellij.platform") version "2.5.0"
}

group = "com.liferay"
version = "1.0.1"

repositories {
    mavenCentral()
    intellijPlatform {
        defaultRepositories()
    }
}

dependencies {
    intellijPlatform {
        intellijIdeaCommunity("2025.1")
        instrumentationTools()
    }
    // Maintained fork of JSch with modern SSH/SFTP support
    implementation("com.github.mwiede:jsch:0.2.21")
}

intellijPlatform {
    pluginConfiguration {
        name = "Liferay Remote Deployer"
        version = "1.0.1"
        description = """
            Deploy Liferay artifacts (.jar/.war) to remote servers via SSH.
            Supports cluster deployments (sequential rolling or parallel),
            automatic server restart with OSGi cache cleanup, and live log streaming.
        """.trimIndent()
        ideaVersion {
            sinceBuild = "251"
        }
    }
}

kotlin {
    jvmToolchain(21)
}
