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

    private val McpTestDetail = structdef {
        field("testId", string)
        field("output", string)
        field("duration", int)
    }

    private val McpRunTestsResponse = structdef {
        field("success", bool)
        field("errorMessage", string)
        field("passCount", int)
        field("failCount", int)
        field("skipCount", int)
        field("inconclusiveCount", int)
        field("failedTests", immutableList(McpTestDetail))
        field("inconclusiveTests", immutableList(McpTestDetail))
    }

    init {
        setting(Kotlin11Generator.Namespace, "com.github.rider.unity.mcp.model")
        setting(CSharp50Generator.Namespace, "RiderUnityTestMcp.Model")

        call("runTests", McpRunTestsRequest, McpRunTestsResponse)
    }
}
