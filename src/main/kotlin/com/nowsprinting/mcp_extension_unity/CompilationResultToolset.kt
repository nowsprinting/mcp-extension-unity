package com.nowsprinting.mcp_extension_unity

import com.intellij.mcpserver.McpToolset
import com.intellij.mcpserver.annotations.McpDescription
import com.intellij.mcpserver.annotations.McpTool
import com.intellij.openapi.diagnostic.Logger
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
        return CompilationErrorResult(errorMessage = "not implemented")
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
