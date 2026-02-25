package com.nowsprinting.mcp_extension_unity

import com.intellij.mcpserver.McpToolset
import com.intellij.mcpserver.annotations.McpDescription
import com.intellij.mcpserver.annotations.McpTool
import com.intellij.mcpserver.project
import com.intellij.openapi.diagnostic.Logger
import com.jetbrains.rd.util.threading.coroutines.asCoroutineDispatcher
import com.jetbrains.rider.plugins.unity.isConnectedToEditor
import com.jetbrains.rider.plugins.unity.model.RunMethodData
import com.jetbrains.rider.plugins.unity.model.frontendBackend.frontendBackendModel
import com.jetbrains.rider.projectView.solution
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.descriptors.buildClassSerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.json.*

class RunMethodInUnityToolset : McpToolset {

    private val LOG = Logger.getInstance(RunMethodInUnityToolset::class.java)

    companion object {
        internal const val LOG_FLUSH_DELAY_MS = 500L

        internal fun validateParam(name: String, value: String?): String? {
            if (value.isNullOrBlank()) return null
            return value.trim()
        }

        internal fun formatErrorMessage(message: String, stackTrace: String): String {
            return if (stackTrace.isBlank()) message
            else "$message\nStack trace:\n$stackTrace"
        }
    }

    @McpTool(name = "run_method_in_unity")
    @McpDescription(description = """
        Invoke a static method in Unity Editor via reflection. The method must be static and parameterless. The method's return value is NOT returned.
        `success` indicates only whether the method was found and invoked. Even if the method throws internally, `success` may be true.
        Console logs during the method will be captured and returned in the `logs` field of the response.
    """)
    suspend fun run_method_in_unity(
        @McpDescription(description = "Assembly name containing the type (e.g., 'Assembly-CSharp-Editor')")
        assemblyName: String? = null,
        @McpDescription(description = "Fully qualified type name (e.g., 'MyNamespace.MyEditorTool')")
        typeName: String? = null,
        @McpDescription(description = "Static method name to invoke (e.g., 'DoSomething')")
        methodName: String? = null
    ): RunMethodInUnityResult {
        val validAssemblyName = validateParam("assemblyName", assemblyName)
            ?: return RunMethodInUnityErrorResult("assemblyName is required and must be non-blank.")

        val validTypeName = validateParam("typeName", typeName)
            ?: return RunMethodInUnityErrorResult("typeName is required and must be non-blank.")

        val validMethodName = validateParam("methodName", methodName)
            ?: return RunMethodInUnityErrorResult("methodName is required and must be non-blank.")

        var collector: UnityConsoleLogCollector? = null
        try {
            val project = currentCoroutineContext().project
            if (!project.isConnectedToEditor()) {
                return RunMethodInUnityErrorResult("Unity Editor is not connected to Rider.")
            }

            val solution = project.solution
            val protocol = solution.protocol
                ?: return RunMethodInUnityErrorResult("No protocol available. The solution may not be fully loaded.")

            val timeoutSeconds = System.getenv("MCP_TOOL_TIMEOUT")?.toLongOrNull()?.takeIf { it > 0 } ?: 300L

            val localCollector = UnityConsoleLogCollector(
                solution.frontendBackendModel.consoleLogging.onConsoleLogEvent
            )
            collector = localCollector

            val response = withTimeout(timeoutSeconds * 1000) {
                withContext(protocol.scheduler.asCoroutineDispatcher) {
                    localCollector.start()
                    solution.frontendBackendModel.runMethodInUnity.startSuspending(
                        RunMethodData(validAssemblyName, validTypeName, validMethodName)
                    )
                }
            }

            delay(LOG_FLUSH_DELAY_MS)

            val logs = withContext(protocol.scheduler.asCoroutineDispatcher) {
                localCollector.stop()
            }
            collector = null

            return if (response.success) {
                RunMethodInUnitySuccessResult(logs)
            } else {
                RunMethodInUnityErrorResult(formatErrorMessage(response.message, response.stackTrace))
            }
        } catch (e: Exception) {
            collector?.stop()
            LOG.error("run_method_in_unity failed", e)
            return RunMethodInUnityErrorResult("${e.javaClass.simpleName}: ${e.message}")
        }
    }
}

@Serializable(with = RunMethodInUnityResultSerializer::class)
sealed interface RunMethodInUnityResult

data class RunMethodInUnityErrorResult(
    val errorMessage: String
) : RunMethodInUnityResult

data class RunMethodInUnitySuccessResult(
    val logs: List<CollectedLogEntry>
) : RunMethodInUnityResult

object RunMethodInUnityResultSerializer : KSerializer<RunMethodInUnityResult> {
    override val descriptor: SerialDescriptor = buildClassSerialDescriptor("RunMethodInUnityResult")

    override fun serialize(encoder: Encoder, value: RunMethodInUnityResult) {
        val jsonEncoder = encoder as JsonEncoder
        val jsonObject = when (value) {
            is RunMethodInUnityErrorResult -> buildJsonObject {
                put("success", false)
                put("errorMessage", value.errorMessage)
            }
            is RunMethodInUnitySuccessResult -> buildJsonObject {
                put("success", true)
                putJsonArray("logs") {
                    value.logs.forEach { entry ->
                        addJsonObject {
                            put("type", entry.type)
                            put("message", entry.message)
                            put("stackTrace", entry.stackTrace)
                        }
                    }
                }
            }
        }
        jsonEncoder.encodeJsonElement(jsonObject)
    }

    override fun deserialize(decoder: Decoder): RunMethodInUnityResult {
        throw UnsupportedOperationException("Deserialization not supported")
    }
}
