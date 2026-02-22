package com.github.rider.unity.mcp

import com.github.rider.unity.mcp.model.*
import com.intellij.mcpserver.McpToolset
import com.intellij.mcpserver.annotations.McpDescription
import com.intellij.mcpserver.annotations.McpTool
import com.intellij.mcpserver.project
import com.jetbrains.rider.projectView.solution
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.serialization.Serializable

class RunUnityTestsToolset : McpToolset {

    @McpTool(name = "run_unity_tests")
    @McpDescription(description = """
        Run Unity tests through Rider's test infrastructure.
        Recommend filtering by assemblyNames, groupNames, or testNames to narrow down the tests to be executed to the scope of changes.
    """)
    suspend fun run_unity_tests(
        @McpDescription(description = "Test mode: EditMode or PlayMode (case insensitive, default: EditMode)")
        testMode: String = "EditMode",
        @McpDescription(description = "The names of assemblies included in the run (without .dll extension, e.g. MyTestAssembly)")
        assemblyNames: List<String>? = null,
        @McpDescription(description = "The name of a Category to include in the run")
        categoryNames: List<String>? = null,
        @McpDescription(description = "Group names supporting Regex (e.g. \"^MyNamespace\\\\.\"). Useful for running specific fixtures or namespaces.")
        groupNames: List<String>? = null,
        @McpDescription(description = "Full test names to match (e.g. MyTestClass2.MyTestWithMultipleValues(1))")
        testNames: List<String>? = null
    ): RunUnityTestsResult {
        val project = currentCoroutineContext().project
        val solution = project.solution
        val protocol = solution.protocol
            ?: return RunUnityTestsResult(error = "No protocol available. The solution may not be fully loaded.")

        val parsedMode = when (testMode.lowercase()) {
            "playmode", "play" -> McpTestMode.PlayMode
            else -> McpTestMode.EditMode
        }

        // Get or create the Rd model bound to the solution protocol.
        // The C# UnityTestMcpHandler also binds UnityTestMcpModel to the same protocol via BindTopLevel,
        // ensuring both sides connect under the same key ("UnityTestMcpModel").
        val model = protocol.getOrCreateExtension(UnityTestMcpModel::class) {
            @Suppress("UNCHECKED_CAST")
            val ctor = UnityTestMcpModel::class.java.getDeclaredConstructor()
            ctor.isAccessible = true
            ctor.newInstance() as UnityTestMcpModel
        }

        val request = McpRunTestsRequest(
            testMode = parsedMode,
            filter = McpTestFilter(
                assemblyNames = assemblyNames ?: emptyList(),
                testNames = testNames ?: emptyList(),
                groupNames = groupNames ?: emptyList(),
                categoryNames = categoryNames ?: emptyList()
            )
        )

        val response = model.runTests.startSuspending(request, protocol.scheduler)

        if (!response.success) {
            return RunUnityTestsResult(error = response.errorMessage)
        }

        return RunUnityTestsResult(
            failCount = response.failCount,
            passCount = response.passCount,
            skipCount = response.skipCount,
            inconclusiveCount = response.inconclusiveCount,
            failedTests = response.failedTests.map {
                TestDetail(testId = it.testId, output = it.output, duration = it.duration)
            },
            inconclusiveTests = response.inconclusiveTests.map {
                TestDetail(testId = it.testId, output = it.output, duration = it.duration)
            }
        )
    }
}

@Serializable
data class TestDetail(
    val testId: String,
    val output: String,
    val duration: Int
)

@Serializable
data class RunUnityTestsResult(
    val error: String? = null,
    val failCount: Int = 0,
    val passCount: Int = 0,
    val skipCount: Int = 0,
    val inconclusiveCount: Int = 0,
    val failedTests: List<TestDetail> = emptyList(),
    val inconclusiveTests: List<TestDetail> = emptyList()
)
