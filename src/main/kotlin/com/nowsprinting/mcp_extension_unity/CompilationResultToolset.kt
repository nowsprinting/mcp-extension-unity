package com.nowsprinting.mcp_extension_unity

import com.intellij.mcpserver.McpToolset
import com.intellij.mcpserver.annotations.McpDescription
import com.intellij.mcpserver.annotations.McpTool
import com.intellij.mcpserver.project
import com.intellij.openapi.diagnostic.Logger
import com.jetbrains.rd.util.threading.coroutines.asCoroutineDispatcher
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

class CompilationResultToolset : McpToolset {

    private val LOG = Logger.getInstance(CompilationResultToolset::class.java)

    companion object {
        internal const val LOG_FLUSH_DELAY_MS = 500L
    }

    @McpTool(name = "get_unity_compilation_result")
    @McpDescription(description = """
        Trigger Unity's AssetDatabase.Refresh() and check if compilation succeeded.
        Console logs during compilation will be captured and returned in the `logs` field of the response.
        Recommended to run this tool to ensure compilation succeeds before `run_unity_tests` or `run_method_in_unity` tool if modified code.
    """)
    suspend fun get_unity_compilation_result(): CompilationResult {
        var collector: UnityConsoleLogCollector? = null
        try {
            val project = currentCoroutineContext().project
            val solution = project.solution
            val protocol = solution.protocol
                ?: return CompilationErrorResult(
                    errorMessage = "No protocol available. The solution may not be fully loaded."
                )

            val timeoutSeconds = System.getenv("MCP_TOOL_TIMEOUT")?.toLongOrNull()?.takeIf { it > 0 } ?: 300L

            val localCollector = UnityConsoleLogCollector(
                solution.frontendBackendModel.consoleLogging.onConsoleLogEvent
            )
            collector = localCollector

            LOG.info("get_unity_compilation_result: calling Rd model.getCompilationResult.startSuspending")
            val response = withTimeout(timeoutSeconds * 1000) {
                withContext(protocol.scheduler.asCoroutineDispatcher) {
                    localCollector.start()
                    val model = UnityCompilationMcpModelProvider.getOrBindModel(protocol)
                    model.getCompilationResult.startSuspending(Unit)
                }
            }
            LOG.info("get_unity_compilation_result: Rd call completed, success=${response.success}")

            delay(LOG_FLUSH_DELAY_MS)

            val logs = withContext(protocol.scheduler.asCoroutineDispatcher) {
                localCollector.stop()
            }
            collector = null

            if (!response.success) {
                return CompilationErrorResult(errorMessage = response.errorMessage, logs = logs)
            }

            return CompilationSuccessResult(logs)
        } catch (e: Exception) {
            collector?.stop()
            LOG.error("get_unity_compilation_result failed", e)
            return CompilationErrorResult(errorMessage = "${e.javaClass.simpleName}: ${e.message}")
        }
    }
}

@Serializable(with = CompilationResultSerializer::class)
sealed interface CompilationResult

data class CompilationErrorResult(
    val errorMessage: String,
    val logs: List<CollectedLogEntry> = emptyList()
) : CompilationResult

data class CompilationSuccessResult(
    val logs: List<CollectedLogEntry>
) : CompilationResult

object CompilationResultSerializer : KSerializer<CompilationResult> {
    override val descriptor: SerialDescriptor = buildClassSerialDescriptor("CompilationResult")

    override fun serialize(encoder: Encoder, value: CompilationResult) {
        val jsonEncoder = encoder as JsonEncoder
        val jsonObject = when (value) {
            is CompilationErrorResult -> buildJsonObject {
                put("success", false)
                put("errorMessage", value.errorMessage)
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
            is CompilationSuccessResult -> buildJsonObject {
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

    override fun deserialize(decoder: Decoder): CompilationResult {
        throw UnsupportedOperationException("Deserialization not supported")
    }
}
