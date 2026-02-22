import org.jetbrains.intellij.platform.gradle.TestFrameworkType

plugins {
    id("java")
    kotlin("jvm") version "2.3.0"
    id("org.jetbrains.intellij.platform") version "2.11.0"
    kotlin("plugin.serialization") version "2.3.0"
}

group = "com.github.rider.unity.mcp"
version = "1.0-SNAPSHOT"

repositories {
    mavenCentral()
    intellijPlatform {
        defaultRepositories()
    }
}

dependencies {
    intellijPlatform {
        // Rider 2025.3.3 (build 253.31033.136)
        create("RD", "2025.3.3")
        testFramework(TestFrameworkType.Platform)
        // MCP Server is bundled in Rider 2025.3+
        bundledPlugin("com.intellij.mcpServer")
    }
    // compileOnly to avoid class collision with the bundled plugin's serialization
    compileOnly("org.jetbrains.kotlinx:kotlinx-serialization-json:1.6.3")
}

kotlin {
    jvmToolchain(21)
}

intellijPlatform {
    pluginConfiguration {
        ideaVersion {
            sinceBuild = "253"
        }

        changeNotes = """
            Initial PoC version
        """.trimIndent()
    }
}
