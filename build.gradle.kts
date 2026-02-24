import org.jetbrains.intellij.platform.gradle.TestFrameworkType
import org.jetbrains.intellij.platform.gradle.tasks.PrepareSandboxTask

plugins {
    id("java")
    kotlin("jvm") version "2.3.0"
    id("org.jetbrains.intellij.platform") version "2.11.0"
    kotlin("plugin.serialization") version "2.3.0"
    id("org.jetbrains.changelog") version "2.2.1"
}

group = providers.gradleProperty("pluginGroup").get()
version = providers.gradleProperty("pluginVersion").get()

val dotNetPluginId: String by project
val buildConfiguration: String by project

repositories {
    mavenCentral()
    intellijPlatform {
        defaultRepositories()
    }
}

// Expose rider-model.jar (= rd.jar in Rider 2025.3+) for the protocol subproject
val riderModel: Configuration by configurations.creating {
    isCanBeConsumed = true
    isCanBeResolved = false
}

artifacts {
    add(riderModel.name, provider {
        // In Rider 2025.3+, rider-model.jar was merged into rd.jar
        intellijPlatform.platformPath.resolve("lib/rd.jar").toFile().also {
            check(it.isFile) { "rd.jar is not found at $it" }
        }
    }) {
        builtBy(org.jetbrains.intellij.platform.gradle.Constants.Tasks.INITIALIZE_INTELLIJ_PLATFORM_PLUGIN)
    }
}

sourceSets {
    main {
        kotlin {
            srcDir("src/main/generated")
        }
    }
}

dependencies {
    intellijPlatform {
        // Rider 2025.3.3 (build 253.31033.136)
        create("RD", "2025.3.3")
        testFramework(TestFrameworkType.Platform)
        // MCP Server is bundled in Rider 2025.3+
        bundledPlugin("com.intellij.mcpServer")
        bundledPlugin("com.intellij.resharper.unity")
    }
    // compileOnly to avoid class collision with the bundled plugin's serialization
    compileOnly("org.jetbrains.kotlinx:kotlinx-serialization-json:1.6.3")
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.6.3")
}

kotlin {
    jvmToolchain(21)
}

intellijPlatform {
    pluginConfiguration {
        ideaVersion {
            sinceBuild = providers.gradleProperty("pluginSinceBuild")
        }

        changeNotes = providers.gradleProperty("pluginVersion").map { pluginVersion ->
            with(changelog) {
                renderItem(
                    (getOrNull(pluginVersion) ?: getUnreleased())
                        .withHeader(false)
                        .withEmptySections(false),
                    org.jetbrains.changelog.Changelog.OutputType.HTML,
                )
            }
        }
    }

    pluginVerification {
        ides {
            // Verify against the same Rider build used for compilation (already downloaded locally)
            local(intellijPlatform.platformPath.toFile())
        }
    }

    signing {
        certificateChain = providers.environmentVariable("CERTIFICATE_CHAIN")
        privateKey = providers.environmentVariable("PRIVATE_KEY")
        password = providers.environmentVariable("PRIVATE_KEY_PASSWORD")
    }

    publishing {
        token = providers.environmentVariable("PUBLISH_TOKEN")
        channels = providers.gradleProperty("pluginVersion").map {
            listOf(it.substringAfter('-', "").substringBefore('.').ifEmpty { "default" })
        }
    }
}

changelog {
    groups.empty()
    repositoryUrl = providers.gradleProperty("pluginRepositoryUrl")
}

val rdGen = ":protocol:rdgen"

tasks {
    withType<org.jetbrains.kotlin.gradle.tasks.KotlinCompile>().configureEach {
        dependsOn(rdGen)
    }

    wrapper {
        gradleVersion = providers.gradleProperty("gradleVersion").get()
    }

    publishPlugin {
        dependsOn(patchChangelog)
    }
}

val generateDotNetSdkProperties by tasks.registering {
    dependsOn(org.jetbrains.intellij.platform.gradle.Constants.Tasks.INITIALIZE_INTELLIJ_PLATFORM_PLUGIN)
    val outputFile = file("src/dotnet/SdkProperties.props")
    outputs.file(outputFile)
    notCompatibleWithConfigurationCache("Accesses intellijPlatform.platformPath at execution time")
    doLast {
        val sdkPath = intellijPlatform.platformPath.toAbsolutePath().toString()
        val riderUnityPluginPath = intellijPlatform.platformPath.resolve("plugins/rider-unity/dotnet").toAbsolutePath().toString()
        outputFile.writeText("""<?xml version="1.0" encoding="utf-8"?>
<Project>
  <PropertyGroup>
    <RiderSdkPath>$sdkPath</RiderSdkPath>
    <RiderUnityPluginPath>$riderUnityPluginPath</RiderUnityPluginPath>
  </PropertyGroup>
</Project>
""")
    }
}

val restoreDotNet by tasks.registering(Exec::class) {
    dependsOn(generateDotNetSdkProperties)
    executable("dotnet")
    args("restore", "src/dotnet/$dotNetPluginId.sln")
}

val compileDotNet by tasks.registering(Exec::class) {
    dependsOn(rdGen)
    dependsOn(restoreDotNet)
    executable("dotnet")
    args(
        "msbuild",
        "src/dotnet/$dotNetPluginId.sln",
        "/p:Configuration=$buildConfiguration",
        "/p:Platform=Any CPU",
        "/consoleloggerparameters:ErrorsOnly"
    )
}

tasks.named<PrepareSandboxTask>("prepareSandbox") {
    dependsOn(compileDotNet)

    val dllDir = file("src/dotnet/$dotNetPluginId/bin/$buildConfiguration/net472")
    from(dllDir) {
        include("$dotNetPluginId.dll")
        include("$dotNetPluginId.pdb")
        into("${rootProject.name}/dotnet")
    }
}
