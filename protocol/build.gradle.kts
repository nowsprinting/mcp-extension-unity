import com.jetbrains.rd.generator.gradle.RdGenTask

plugins {
    kotlin("jvm") version "2.3.0"
    id("com.jetbrains.rdgen")
}

repositories {
    mavenCentral()
}

dependencies {
    compileOnly(kotlin("stdlib"))
    implementation("com.jetbrains.rd:rd-gen:${property("rdGenVersion")}")
}

val kotlinGeneratedDir = rootDir.resolve("src/main/generated")
val csharpGeneratedDir = rootDir.resolve("src/dotnet/${property("dotNetPluginId")}/Model")

rdgen {
    verbose = true
    packages = "model.rider"

    generator {
        language = "kotlin"
        transform = "asis"
        root = "model.rider.UnityTestMcpModel"
        directory = kotlinGeneratedDir.absolutePath
        generatedFileSuffix = ".Generated"
    }

    generator {
        language = "csharp"
        transform = "reversed"
        root = "model.rider.UnityTestMcpModel"
        namespace = "RiderUnityTestMcp.Model"
        directory = csharpGeneratedDir.absolutePath
        generatedFileSuffix = ".Generated"
    }

    generator {
        language = "kotlin"
        transform = "asis"
        root = "model.rider.UnityCompilationMcpModel"
        directory = kotlinGeneratedDir.absolutePath
        generatedFileSuffix = ".Generated"
    }

    generator {
        language = "csharp"
        transform = "reversed"
        root = "model.rider.UnityCompilationMcpModel"
        namespace = "McpExtensionUnity.Model"
        directory = csharpGeneratedDir.absolutePath
        generatedFileSuffix = ".Generated"
    }
}

tasks.withType<RdGenTask>().configureEach {
    classpath(sourceSets["main"].runtimeClasspath)
    dependsOn("compileKotlin")
    // rd-gen 2026.2.5 dropped its own sources()/hashFolder change-skip, so declare
    // inputs/outputs here for Gradle's up-to-date check to skip the task instead.
    inputs.dir(projectDir.resolve("src/main/kotlin"))
    outputs.dirs(kotlinGeneratedDir, csharpGeneratedDir)
}

kotlin {
    jvmToolchain(21)
}
