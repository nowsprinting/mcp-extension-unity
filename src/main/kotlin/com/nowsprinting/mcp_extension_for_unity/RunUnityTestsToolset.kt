package com.nowsprinting.mcp_extension_for_unity

import com.nowsprinting.mcp_extension_for_unity.model.*
import com.intellij.mcpserver.McpToolset
import com.intellij.mcpserver.annotations.McpDescription
import com.intellij.mcpserver.annotations.McpTool
import com.intellij.mcpserver.project
import com.intellij.openapi.diagnostic.Logger
import com.jetbrains.rd.framework.IProtocol
import com.jetbrains.rd.framework.RdId
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
import java.util.concurrent.ConcurrentHashMap

class RunUnityTestsToolset : McpToolset {

    private val LOG = Logger.getInstance(RunUnityTestsToolset::class.java)

    companion object {
        private val models = ConcurrentHashMap<IProtocol, UnityTestMcpModel>()

        private fun getOrBindModel(protocol: IProtocol): UnityTestMcpModel {
            return models.computeIfAbsent(protocol) { proto ->
                val ctor = UnityTestMcpModel::class.java.getDeclaredConstructor()
                ctor.isAccessible = true
                val model = ctor.newInstance() as UnityTestMcpModel
                // Match C# generated constructor: Identify + BindTopLevel
                // RdId.Null (Kotlin) == RdId.Root (C#) == RdId(0)
                model.identify(proto.identity, RdId.Null.mix("UnityTestMcpModel"))
                model.preBind(proto.lifetime, proto, "UnityTestMcpModel")
                model.bind()
                proto.lifetime.onTermination { models.remove(proto) }
                model
            }
        }

        internal fun sanitizeAssemblyNames(assemblyNames: List<String>?): List<String> {
            return assemblyNames?.filter { it.isNotBlank() } ?: emptyList()
        }

        internal fun parseTestMode(testMode: String): McpTestMode? {
            return when (testMode.trim().lowercase()) {
                "editmode", "edit" -> McpTestMode.EditMode
                "playmode", "play" -> McpTestMode.PlayMode
                else -> null
            }
        }
    }

    @McpTool(name = "run_unity_tests")
    @McpDescription(description = """
        Run Unity tests through Rider's test infrastructure.
        IMPORTANT: assemblyNames is required. Unity's test runner matches tests by assembly name,
        so omitting it results in no tests being found and may disconnect the Unity Editor.
        Find assembly names in your project's .asmdef files or Rider's Unit Test Explorer
        (e.g. 'MyTests.EditMode', 'MyTests.PlayMode').
        Recommend filtering by assemblyNames, groupNames, or testNames to narrow down the tests to the scope of changes.
    """)
    suspend fun run_unity_tests(
        @McpDescription(description = "Test mode: EditMode or PlayMode (case insensitive, default: EditMode)")
        testMode: String = "EditMode",
        @McpDescription(description = "REQUIRED. The names of assemblies included in the run (without .dll extension, e.g. MyTestAssembly). Find them in .asmdef files or Rider's Unit Test Explorer.")
        assemblyNames: List<String>? = null,
        @McpDescription(description = "The name of a Category to include in the run")
        categoryNames: List<String>? = null,
        @McpDescription(description = "Group names supporting Regex (e.g. \"^MyNamespace\\\\.\"). Useful for running specific fixtures or namespaces.")
        groupNames: List<String>? = null,
        @McpDescription(description = "Full test names to match (e.g. MyTestClass2.MyTestWithMultipleValues(1))")
        testNames: List<String>? = null
    ): RunUnityTestsResult {
        try {
            val effectiveAssemblyNames = sanitizeAssemblyNames(assemblyNames)
            if (effectiveAssemblyNames.isEmpty()) {
                return TestErrorResult(
                    message = "assemblyNames is required and must contain at least one non-empty assembly name. " +
                            "Unity's test runner requires explicit assembly names to match tests. " +
                            "Find assembly names in your project's .asmdef files or Rider's Unit Test Explorer " +
                            "(e.g. 'MyTests.EditMode', 'MyTests.PlayMode')."
                )
            }

            val project = currentCoroutineContext().project
            val solution = project.solution
            val protocol = solution.protocol
                ?: return TestErrorResult(
                    message = "No protocol available. The solution may not be fully loaded."
                )

            val parsedMode = parseTestMode(testMode)
                ?: return TestErrorResult(
                    message = "Invalid testMode: '$testMode'. Valid values: EditMode, edit, PlayMode, play (case insensitive)."
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
                val model = getOrBindModel(protocol)
                model.runTests.startSuspending(request)
            }
            LOG.info("run_unity_tests: Rd call completed, success=${response.success}")

            if (!response.success) {
                return TestErrorResult(message = response.errorMessage)
            }

            return TestRunResult(
                passCount = response.passCount,
                skipCount = response.skipCount,
                failCount = response.failCount,
                inconclusiveCount = response.inconclusiveCount,
                failedTests = response.failedTests.map {
                    TestDetail(testId = it.testId, output = it.output, duration = it.duration)
                },
                inconclusiveTests = response.inconclusiveTests.map {
                    TestDetail(testId = it.testId, output = it.output, duration = it.duration)
                }
            )
        } catch (e: Exception) {
            LOG.error("run_unity_tests failed", e)
            return TestErrorResult(message = "${e.javaClass.simpleName}: ${e.message}")
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
    val message: String
) : RunUnityTestsResult

data class TestRunResult(
    val passCount: Int,
    val skipCount: Int,
    val failCount: Int,
    val inconclusiveCount: Int,
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
                put("message", value.message)
            }
            is TestRunResult -> buildJsonObject {
                put("success", value.success)
                put("passCount", value.passCount)
                put("skipCount", value.skipCount)
                put("failCount", value.failCount)
                put("inconclusiveCount", value.inconclusiveCount)
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
