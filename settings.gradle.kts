rootProject.name = "rider-unity-test-mcp-plugin"
include(":protocol")

pluginManagement {
    val rdGenVersion: String by settings

    repositories {
        maven("https://cache-redirector.jetbrains.com/intellij-dependencies")
        gradlePluginPortal()
        mavenCentral()
    }
    plugins {
        id("com.jetbrains.rdgen") version rdGenVersion
    }
    resolutionStrategy {
        eachPlugin {
            if (requested.id.id == "com.jetbrains.rdgen") {
                useModule("com.jetbrains.rd:rd-gen:$rdGenVersion")
            }
        }
    }
}

plugins {
    id("org.gradle.toolchains.foojay-resolver-convention") version "1.0.0"
}
