package com.github.rider.unity.mcp

import com.intellij.mcpserver.McpToolset
import com.intellij.mcpserver.annotations.McpDescription
import com.intellij.mcpserver.annotations.McpTool
import kotlinx.serialization.Serializable

class RunUnityTestsToolset : McpToolset {

    @McpTool(name = "run_unity_tests")
    @McpDescription(description = "Run Unity tests through Rider's test infrastructure")
    suspend fun run_unity_tests(
        @McpDescription(description = "Test mode: EditMode or PlayMode")
        testMode: String = "EditMode",
        @McpDescription(description = "Assembly names to filter tests")
        assemblyNames: List<String>? = null,
        @McpDescription(description = "Specific test names to run")
        testNames: List<String>? = null,
        @McpDescription(description = "Test group names to filter")
        groupNames: List<String>? = null,
        @McpDescription(description = "Test categories to filter")
        testCategories: List<String>? = null
    ): RunUnityTestsResult {
        // PoC: echo back args only; actual test execution to be implemented later
        return RunUnityTestsResult(
            status = "poc_echo",
            testMode = testMode,
            assemblyNames = assemblyNames,
            testNames = testNames,
            groupNames = groupNames,
            testCategories = testCategories
        )
    }
}

@Serializable
data class RunUnityTestsResult(
    val status: String,
    val testMode: String,
    val assemblyNames: List<String>?,
    val testNames: List<String>?,
    val groupNames: List<String>?,
    val testCategories: List<String>?
)
