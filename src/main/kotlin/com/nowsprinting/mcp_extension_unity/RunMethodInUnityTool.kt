package com.nowsprinting.mcp_extension_unity

import com.intellij.mcpserver.project
import com.intellij.openapi.diagnostic.Logger
import com.jetbrains.rd.util.threading.coroutines.asCoroutineDispatcher
import com.jetbrains.rider.plugins.unity.model.RunMethodData
import com.jetbrains.rider.plugins.unity.model.frontendBackend.frontendBackendModel
import com.jetbrains.rider.projectView.solution
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.descriptors.buildClassSerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.json.*

class RunMethodInUnityTool {

    private val LOG = Logger.getInstance(RunMethodInUnityTool::class.java)

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

    suspend fun run_method_in_unity(
        assemblyName: String,
        typeName: String,
        methodName: String
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
            val solution = project.solution
            if (!EditorConnectionUtils.awaitEditorConnection(solution.frontendBackendModel.unityEditorConnected)) {
                return RunMethodInUnityErrorResult(
                    "Unity Editor did not connect within 30 seconds. Check idea.log and Editor.log to understand the situation. If Editor not running, use the `execute_run_configuration` tool to launch the `Start Unity` configuration, then retry.")
            }
            val protocol = solution.protocol
                ?: return RunMethodInUnityErrorResult("No protocol available. The solution may not be fully loaded.")

            val localCollector = UnityConsoleLogCollector(
                solution.frontendBackendModel.consoleLogging.onConsoleLogEvent
            )
            collector = localCollector

            // WHY NOT withTimeout here:
            // runMethodInUnity is a resharper-unity Rd RPC with its own backend timeout.
            // Cancelling from the Kotlin side would orphan the in-flight RPC on the C# side;
            // a retry from the caller could then invoke the method a second time.
            val response = withContext(protocol.scheduler.asCoroutineDispatcher) {
                localCollector.start()
                solution.frontendBackendModel.runMethodInUnity.startSuspending(
                    RunMethodData(validAssemblyName, validTypeName, validMethodName)
                )
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
        } catch (e: kotlinx.coroutines.CancellationException) {
            // CancellationException is a control-flow signal (coroutine cancelled by MCP SDK timeout),
            // not a true error. Re-throwing allows the coroutine framework to propagate it correctly.
            throw e
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
