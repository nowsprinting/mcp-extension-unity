package com.github.rider.unity.mcp

import com.intellij.mcpserver.McpToolset
import com.intellij.mcpserver.annotations.McpDescription
import com.intellij.mcpserver.annotations.McpTool
import com.intellij.mcpserver.project
import com.jetbrains.rider.plugins.unity.isConnectedToEditor
import com.jetbrains.rider.plugins.unity.model.frontendBackend.frontendBackendModel
import com.jetbrains.rider.projectView.solution
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.serialization.Serializable

class RunUnityTestsToolset : McpToolset {

    @McpTool(name = "run_unity_tests")
    @McpDescription(description = "Run Unity tests through Rider's test infrastructure")
    suspend fun run_unity_tests(
        @McpDescription(description = "Test mode: EditMode or PlayMode")
        testMode: String = "EditMode",
        @McpDescription(description = "Assembly names to filter tests")
        assemblyNames: List<String>? = null,
        @McpDescription(description = "Specific test names to run")
        testNames: List<String>? = null,
        @McpDescription(description = "Test group names to filter")
        groupNames: List<String>? = null,
        @McpDescription(description = "Test categories to filter")
        testCategories: List<String>? = null
    ): RunUnityTestsResult {
        val project = currentCoroutineContext().project
        val model = project.solution.frontendBackendModel
        val isConnected = project.isConnectedToEditor()
        val editorState = model.unityEditorState.valueOrNull?.toString()
        val preference = model.unitTestPreference.value?.toString()

        if (!isConnected) {
            return RunUnityTestsResult(
                status = "error",
                message = "Unity Editor is not connected to Rider. Please open Unity Editor with the project.",
                unityEditorConnected = false,
                unityEditorState = editorState,
                unitTestPreference = preference,
                testMode = testMode,
                assemblyNames = assemblyNames,
                testNames = testNames,
                groupNames = groupNames,
                testCategories = testCategories
            )
        }

        return RunUnityTestsResult(
            status = "connected",
            message = "Unity Editor is connected. Test execution not yet implemented (Step 6+).",
            unityEditorConnected = true,
            unityEditorState = editorState,
            unitTestPreference = preference,
            testMode = testMode,
            assemblyNames = assemblyNames,
            testNames = testNames,
            groupNames = groupNames,
            testCategories = testCategories
        )
    }
}

@Serializable
data class RunUnityTestsResult(
    val status: String,
    val message: String,
    val unityEditorConnected: Boolean,
    val unityEditorState: String?,
    val unitTestPreference: String?,
    val testMode: String,
    val assemblyNames: List<String>? = null,
    val testNames: List<String>? = null,
    val groupNames: List<String>? = null,
    val testCategories: List<String>? = null
)
