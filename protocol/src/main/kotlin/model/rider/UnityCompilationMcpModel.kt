package model.rider

import com.jetbrains.rd.generator.nova.*
import com.jetbrains.rd.generator.nova.PredefinedType.bool
import com.jetbrains.rd.generator.nova.PredefinedType.string
import com.jetbrains.rd.generator.nova.PredefinedType.void
import com.jetbrains.rd.generator.nova.csharp.CSharp50Generator
import com.jetbrains.rd.generator.nova.kotlin.Kotlin11Generator

@Suppress("unused")
object UnityCompilationMcpModel : Root() {

    private val McpCompilationResponse = structdef {
        field("success", bool)
        field("errorMessage", string)
    }

    init {
        setting(Kotlin11Generator.Namespace, "com.nowsprinting.mcp_extension_unity.model")
        setting(CSharp50Generator.Namespace, "McpExtensionUnity.Model")

        call("getCompilationResult", void, McpCompilationResponse)
    }
}
