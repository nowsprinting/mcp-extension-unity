import org.jetbrains.changelog.Changelog
import org.jetbrains.changelog.markdownToHTML
import org.jetbrains.intellij.platform.gradle.TestFrameworkType
import org.jetbrains.intellij.platform.gradle.tasks.PrepareSandboxTask

plugins {
    id("java")
    kotlin("jvm") version "2.3.0"
    id("org.jetbrains.intellij.platform") version "2.17.0"
    kotlin("plugin.serialization") version "2.3.0"
    id("org.jetbrains.changelog") version "2.2.1"
}

group = providers.gradleProperty("pluginGroup").get()
val gitDescribe = providers.exec {
    commandLine("git", "describe", "--tags")
}.standardOutput.asText.map { it.trim().removePrefix("v") }

version = providers.gradleProperty("buildVersion")
    .orElse(gitDescribe)
    .get()

val dotNetPluginId: String by project
val buildConfiguration: String by project

repositories {
    mavenCentral()
    intellijPlatform {
        defaultRepositories()
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
        // Rider 2026.2 (build 262.x). useInstaller = false is required for any Rider target —
        // useInstaller = true (the default) is not supported for Rider and fails resolution:
        // https://github.com/JetBrains/intellij-platform-gradle-plugin/issues/1852
        // platformVersion must be the exact Maven-published coordinate (an EAP build's "-SNAPSHOT"
        // string, or a stable release's plain version); bump it when moving to a newer EAP or GA.
        create(
            providers.gradleProperty("platformType").get(),
            providers.gradleProperty("platformVersion").get(),
        ) {
            useInstaller = false
        }
        testFramework(TestFrameworkType.Platform)
        // MCP Server is bundled in Rider 2025.3+
        bundledPlugin("com.intellij.mcpServer")
        bundledPlugin("com.intellij.resharper.unity")
        // Rider 2026.2 split Project.solution (SolutionHostExtensionsKt) out of the core platform
        // modules and into this module; the previous "RD" target dependency exposed it implicitly.
        bundledModule("intellij.rider.rdclient.dotnet")
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

        // Extract the <!-- Plugin description --> section from README.md and provide for the plugin's manifest
        description = providers.fileContents(layout.projectDirectory.file("README.md")).asText.map {
            val start = "<!-- Plugin description -->"
            val end = "<!-- Plugin description end -->"

            with(it.lines()) {
                if (!containsAll(listOf(start, end))) {
                    throw GradleException("Plugin description section not found in README.md:\n$start ... $end")
                }
                subList(indexOf(start) + 1, indexOf(end)).joinToString("\n").let(::markdownToHTML)
            }
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
            // compatibility-verification.yml passes -PverifyIdePaths (comma-separated local IDE home
            // directories, manually downloaded+extracted) to check against the IDE builds JetBrains
            // Marketplace verifies against (latest RELEASE + latest EAP within pluginSinceBuild..
            // pluginUntilBuild). This bypasses the Gradle plugin's Rider dependency resolution, which
            // is broken for EAP-channel artifacts: https://github.com/JetBrains/intellij-platform-gradle-plugin/issues/1852
            // Without the property (build.yml's verify job and local dev), fall back to the
            // already-downloaded Rider build — existing behavior is unchanged.
            val verifyIdePaths = providers.gradleProperty("verifyIdePaths")
            if (verifyIdePaths.isPresent) {
                verifyIdePaths.get().split(",").map { it.trim() }.filter { it.isNotEmpty() }.forEach { path ->
                    local(File(path))
                }
            } else {
                local(intellijPlatform.platformPath.toFile())
            }
        }
    }

    signing {
        certificateChain = providers.environmentVariable("CERTIFICATE_CHAIN")
        privateKey = providers.environmentVariable("PRIVATE_KEY")
        password = providers.environmentVariable("PRIVATE_KEY_PASSWORD")
    }

    publishing {
        token = providers.environmentVariable("PUBLISH_TOKEN")
        channels = providers.gradleProperty("pluginVersion").map { pluginVersion ->
            val channel = pluginVersion.substringAfter('-', "").substringBefore('.').ifEmpty { "default" }
            val platformVersion = providers.gradleProperty("platformVersion").get()
            // Guard against publishing a build compiled against an EAP/snapshot SDK to the
            // default (stable) channel — easy to do by forgetting the tag's channel suffix
            // (e.g. "-eap.1") when cutting a release while platformVersion is still pre-release.
            if (channel == "default" && Regex("(?i)(EAP|SNAPSHOT|-RC\\d*$)").containsMatchIn(platformVersion)) {
                throw GradleException(
                    "platformVersion ('$platformVersion') looks like an EAP/pre-release build, but " +
                        "pluginVersion ('$pluginVersion') has no channel suffix, which would publish to the " +
                        "default (stable) channel. Tag the release with a channel suffix, e.g. 'v2.0.0-eap.1'."
                )
            }
            listOf(channel)
        }
    }
}

changelog {
    version = providers.gradleProperty("pluginVersion")
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
