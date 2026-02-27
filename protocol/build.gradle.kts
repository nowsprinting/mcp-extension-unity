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

val modelDir = projectDir.resolve("src/main/kotlin")
val kotlinGeneratedDir = rootDir.resolve("src/main/generated")
val csharpGeneratedDir = rootDir.resolve("src/dotnet/${property("dotNetPluginId")}/Model")

rdgen {
    verbose = true
    packages = "model.rider"
    sources(modelDir)
    hashFolder = layout.buildDirectory.dir("rdgen/hashes").get().asFile.absolutePath

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
}

kotlin {
    jvmToolchain(21)
}
