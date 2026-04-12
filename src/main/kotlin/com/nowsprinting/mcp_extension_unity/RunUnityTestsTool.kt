package com.nowsprinting.mcp_extension_unity

import com.nowsprinting.mcp_extension_unity.model.*
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

class RunUnityTestsTool {

    private val LOG = Logger.getInstance(RunUnityTestsTool::class.java)

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

            return TestRunResult(
                passCount = passCount,
                failCount = failCount,
                inconclusiveCount = inconclusiveCount,
                skipCount = skipCount,
                failedTests = failedTests,
                inconclusiveTests = inconclusiveTests
            )
        }
    }

    suspend fun run_unity_tests(
        testMode: String? = null,
        assemblyNames: List<String>? = null,
        categoryNames: List<String>? = null,
        groupNames: List<String>? = null,
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
            }
        }
        jsonEncoder.encodeJsonElement(jsonObject)
    }

    override fun deserialize(decoder: Decoder): RunUnityTestsResult {
        throw UnsupportedOperationException("Deserialization not supported")
    }
}
