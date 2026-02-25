package com.nowsprinting.mcp_extension_unity

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

class CompilationResultToolset : McpToolset {

    private val LOG = Logger.getInstance(CompilationResultToolset::class.java)

    @McpTool(name = "get_unity_compilation_result")
    @McpDescription(description = """
        Trigger Unity's AssetDatabase.Refresh() and check if compilation succeeded.
        Useful for verifying that code changes compile before running tests.
    """)
    suspend fun get_unity_compilation_result(): CompilationResult {
        try {
            val project = currentCoroutineContext().project
            val solution = project.solution
            val protocol = solution.protocol
                ?: return CompilationErrorResult(
                    errorMessage = "No protocol available. The solution may not be fully loaded."
                )

            LOG.info("get_unity_compilation_result: calling Rd model.getCompilationResult.startSuspending")
            val response = withContext(protocol.scheduler.asCoroutineDispatcher) {
                val model = UnityTestMcpModelProvider.getOrBindModel(protocol)
                model.getCompilationResult.startSuspending(Unit)
            }
            LOG.info("get_unity_compilation_result: Rd call completed, success=${response.success}")

            if (!response.success) {
                return CompilationErrorResult(errorMessage = response.errorMessage)
            }

            return CompilationSuccessResult
        } catch (e: Exception) {
            LOG.error("get_unity_compilation_result failed", e)
            return CompilationErrorResult(errorMessage = "${e.javaClass.simpleName}: ${e.message}")
        }
    }
}

@Serializable(with = CompilationResultSerializer::class)
sealed interface CompilationResult

data class CompilationErrorResult(
    val errorMessage: String
) : CompilationResult

object CompilationSuccessResult : CompilationResult

object CompilationResultSerializer : KSerializer<CompilationResult> {
    override val descriptor: SerialDescriptor = buildClassSerialDescriptor("CompilationResult")

    override fun serialize(encoder: Encoder, value: CompilationResult) {
        val jsonEncoder = encoder as JsonEncoder
        val jsonObject = when (value) {
            is CompilationErrorResult -> buildJsonObject {
                put("success", false)
                put("errorMessage", value.errorMessage)
            }
            is CompilationSuccessResult -> buildJsonObject {
                put("success", true)
            }
        }
        jsonEncoder.encodeJsonElement(jsonObject)
    }

    override fun deserialize(decoder: Decoder): CompilationResult {
        throw UnsupportedOperationException("Deserialization not supported")
    }
}
