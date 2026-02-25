package com.nowsprinting.mcp_extension_unity

import com.nowsprinting.mcp_extension_unity.model.*
import com.intellij.mcpserver.McpToolset
import com.intellij.mcpserver.annotations.McpDescription
import com.intellij.mcpserver.annotations.McpTool
import com.intellij.mcpserver.project
import com.intellij.openapi.diagnostic.Logger
import com.jetbrains.rd.util.threading.coroutines.asCoroutineDispatcher
import com.jetbrains.rider.projectView.solution
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.withContext
import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.descriptors.buildClassSerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.json.*

class RunUnityTestsToolset : McpToolset {

    private val LOG = Logger.getInstance(RunUnityTestsToolset::class.java)

    companion object {
        internal fun sanitizeAssemblyNames(assemblyNames: List<String>?): List<String> {
            return assemblyNames?.filter { it.isNotBlank() } ?: emptyList()
        }

        internal fun parseTestMode(testMode: String?): McpTestMode? {
            if (testMode == null) return null
            return when (testMode.trim().lowercase()) {
                "editmode", "edit" -> McpTestMode.EditMode
                "playmode", "play" -> McpTestMode.PlayMode
                else -> null
            }
        }

        internal fun filterLeafResults(results: List<McpTestResultItem>): TestRunResult {
            val parentIds = results.mapNotNullTo(mutableSetOf()) {
                it.parentId.takeIf { id -> id.isNotBlank() }
            }
            val leaves = results.filter { it.testId !in parentIds }

            var passCount = 0
            var skipCount = 0
            var failCount = 0
            var inconclusiveCount = 0
            val failedTests = mutableListOf<TestDetail>()
            val inconclusiveTests = mutableListOf<TestDetail>()

            for (leaf in leaves) {
                when (leaf.status) {
                    McpTestResultStatus.Success -> passCount++
                    McpTestResultStatus.Failure -> {
                        failCount++
                        failedTests.add(TestDetail(testId = leaf.testId, output = leaf.output, duration = leaf.duration))
                    }
                    McpTestResultStatus.Ignored -> skipCount++
                    McpTestResultStatus.Inconclusive -> {
                        inconclusiveCount++
                        inconclusiveTests.add(TestDetail(testId = leaf.testId, output = leaf.output, duration = leaf.duration))
                    }
                }
            }

            return TestRunResult(passCount, failCount, inconclusiveCount, skipCount, failedTests, inconclusiveTests)
        }
    }

    @McpTool(name = "run_unity_tests")
    @McpDescription(description = """
        Run tests on Unity Test Runner through Rider's test infrastructure.
        Recommend filtering by assemblyNames, categoryNames, groupNames, and testNames to narrow down the tests to the scope of changes.
    """)
    suspend fun run_unity_tests(
        @McpDescription(description = "REQUIRED. `EditMode` or `PlayMode` (case insensitive). If the `includePlatforms` in the assembly definition file (.asmdef) contains `Editor`, it is an Edit Mode test; otherwise, it is a Play Mode test.")
        testMode: String? = null,
        @McpDescription(description = "REQUIRED. Names of test assemblies to run (without .dll extension, e.g., 'MyFeature.Tests'). Specify the `name` property in the assembly definition file.")
        assemblyNames: List<String>? = null,
        @McpDescription(description = "Names of a category to include in the run. Any test or fixture runs that have a category matching the string.")
        categoryNames: List<String>? = null,
        @McpDescription(description = "Same as testNames, except that it allows for Regex. This is useful for running specific fixtures or namespaces.")
        groupNames: List<String>? = null,
        @McpDescription(description = "The full name of the tests to match the filter. This is usually in the format FixtureName.TestName. If the test has test arguments, then include them in parentheses.")
        testNames: List<String>? = null
    ): RunUnityTestsResult {
        try {
            val project = currentCoroutineContext().project
            val solution = project.solution
            val protocol = solution.protocol
                ?: return TestErrorResult(
                    errorMessage ="No protocol available. The solution may not be fully loaded."
                )

            val effectiveAssemblyNames = sanitizeAssemblyNames(assemblyNames)
            if (effectiveAssemblyNames.isEmpty()) {
                return TestErrorResult(
                    errorMessage ="assemblyNames is required and must contain at least one non-empty assembly name."
                )
            }

            val parsedMode = parseTestMode(testMode)
                ?: return TestErrorResult(
                    errorMessage =if (testMode == null)
                        "testMode is required. Valid values: EditMode, PlayMode (case insensitive)."
                    else
                        "Invalid testMode: '$testMode'. Valid values: EditMode, edit, PlayMode, play (case insensitive)."
                )

            val request = McpRunTestsRequest(
                testMode = parsedMode,
                filter = McpTestFilter(
                    assemblyNames = effectiveAssemblyNames,
                    testNames = testNames ?: emptyList(),
                    groupNames = groupNames ?: emptyList(),
                    categoryNames = categoryNames ?: emptyList()
                )
            )

            LOG.info("run_unity_tests: calling Rd model.runTests.startSuspending")
            val response = withContext(protocol.scheduler.asCoroutineDispatcher) {
                val model = UnityTestMcpModelProvider.getOrBindModel(protocol)
                model.runTests.startSuspending(request)
            }
            LOG.info("run_unity_tests: Rd call completed, success=${response.success}")

            if (!response.success) {
                return TestErrorResult(errorMessage =response.errorMessage)
            }

            return filterLeafResults(response.testResults)
        } catch (e: Exception) {
            LOG.error("run_unity_tests failed", e)
            return TestErrorResult(errorMessage ="${e.javaClass.simpleName}: ${e.message}")
        }
    }
}

@Serializable
data class TestDetail(
    val testId: String,
    val output: String,
    val duration: Int
)

@Serializable(with = RunUnityTestsResultSerializer::class)
sealed interface RunUnityTestsResult

data class TestErrorResult(
    val errorMessage: String
) : RunUnityTestsResult

data class TestRunResult(
    val passCount: Int,
    val failCount: Int,
    val inconclusiveCount: Int,
    val skipCount: Int,
    val failedTests: List<TestDetail>,
    val inconclusiveTests: List<TestDetail>
) : RunUnityTestsResult {
    val success: Boolean
        get() = failCount == 0 && inconclusiveCount == 0 && passCount > 0
}

object RunUnityTestsResultSerializer : KSerializer<RunUnityTestsResult> {
    override val descriptor: SerialDescriptor = buildClassSerialDescriptor("RunUnityTestsResult")

    override fun serialize(encoder: Encoder, value: RunUnityTestsResult) {
        val jsonEncoder = encoder as JsonEncoder
        val jsonObject = when (value) {
            is TestErrorResult -> buildJsonObject {
                put("success", false)
                put("errorMessage", value.errorMessage)
            }
            is TestRunResult -> buildJsonObject {
                put("success", value.success)
                put("passCount", value.passCount)
                put("failCount", value.failCount)
                put("inconclusiveCount", value.inconclusiveCount)
                put("skipCount", value.skipCount)
                putJsonArray("failedTests") {
                    value.failedTests.forEach { detail ->
                        addJsonObject {
                            put("testId", detail.testId)
                            put("output", detail.output)
                            put("duration", detail.duration)
                        }
                    }
                }
                putJsonArray("inconclusiveTests") {
                    value.inconclusiveTests.forEach { detail ->
                        addJsonObject {
                            put("testId", detail.testId)
                            put("output", detail.output)
                            put("duration", detail.duration)
                        }
                    }
                }
                put("errorMessage", "")
            }
        }
        jsonEncoder.encodeJsonElement(jsonObject)
    }

    override fun deserialize(decoder: Decoder): RunUnityTestsResult {
        throw UnsupportedOperationException("Deserialization not supported")
    }
}
