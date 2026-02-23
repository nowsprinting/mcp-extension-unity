package model.rider

import com.jetbrains.rd.generator.nova.*
import com.jetbrains.rd.generator.nova.PredefinedType.bool
import com.jetbrains.rd.generator.nova.PredefinedType.int
import com.jetbrains.rd.generator.nova.PredefinedType.string
import com.jetbrains.rd.generator.nova.csharp.CSharp50Generator
import com.jetbrains.rd.generator.nova.kotlin.Kotlin11Generator

@Suppress("unused")
object UnityTestMcpModel : Root() {

    private val McpTestMode = enum {
        +"EditMode"
        +"PlayMode"
    }

    private val McpTestFilter = structdef {
        field("assemblyNames", immutableList(string))
        field("testNames", immutableList(string))
        field("groupNames", immutableList(string))
        field("categoryNames", immutableList(string))
    }

    private val McpRunTestsRequest = structdef {
        field("testMode", McpTestMode)
        field("filter", McpTestFilter)
    }

    private val McpTestResultStatus = enum {
        +"Success"
        +"Failure"
        +"Ignored"
        +"Inconclusive"
    }

    private val McpTestResultItem = structdef {
        field("testId", string)
        field("parentId", string)
        field("output", string)
        field("duration", int)
        field("status", McpTestResultStatus)
    }

    private val McpRunTestsResponse = structdef {
        field("success", bool)
        field("errorMessage", string)
        field("testResults", immutableList(McpTestResultItem))
    }

    init {
        setting(Kotlin11Generator.Namespace, "com.nowsprinting.mcp_extension_for_unity.model")
        setting(CSharp50Generator.Namespace, "McpExtensionForUnity.Model")

        call("runTests", McpRunTestsRequest, McpRunTestsResponse)
    }
}
