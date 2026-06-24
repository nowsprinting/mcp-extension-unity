package com.nowsprinting.mcp_extension_unity

import com.intellij.mcpserver.McpToolset
import com.intellij.mcpserver.annotations.McpDescription
import com.intellij.mcpserver.annotations.McpTool

class UnityEditorToolset : McpToolset {

    private val compilationResultTool = CompilationResultTool()
    private val runUnityTestsTool = RunUnityTestsTool()
    private val runMethodInUnityTool = RunMethodInUnityTool()
    private val playControlTool = PlayControlTool()

    @McpTool(name = "get_unity_compilation_result")
    @McpDescription(description = """
        Trigger Unity's AssetDatabase.Refresh() and check if compilation succeeded.
        Recommended to run this tool to ensure compilation succeeds before `run_unity_tests` or `run_method_in_unity` tool if modified code.
    """)
    suspend fun get_unity_compilation_result(): CompilationResult =
        compilationResultTool.get_unity_compilation_result()

    @McpTool(name = "run_unity_tests")
    @McpDescription(description = """
        Run tests on Unity Editor through Rider's test infrastructure.
        Recommend filtering by `assemblyNames`, `categoryNames`, `groupNames`, and `testNames` to narrow down the tests to the scope of changes.

        Identify Assembly and Test Mode:
        1. Find the assembly definition file (.asmdef) in the parent directory hierarchy of the target file.
        2. The assembly name is the `name` property in the .asmdef file.
        3. If `includePlatforms` in the .asmdef contains `Editor`, it is EditMode; otherwise PlayMode.

        IMPORTANT: If you have modified any C# source files, call `get_unity_compilation_result` first to trigger a refresh and verify compilation succeeds before running tests.

        IMPORTANT: This tool blocks until the test run completes or times out. Do not call it again while waiting — duplicate calls launch a second test run on top of the first. On timeout, before retrying or adjusting filters, check idea.log and Editor.log for signs of an infinite loop in a test.
    """)
    suspend fun run_unity_tests(
        @McpDescription(description = "`EditMode` or `PlayMode` (case insensitive).")
        testMode: String,
        @McpDescription(description = "Names of test assemblies to run (without .dll extension, e.g., 'MyFeature.Tests').")
        assemblyNames: List<String>,
        @McpDescription(description = "Names of a category to include in the run. Any test or fixture runs that have a category matching the string. Specify when the test class/method is decorated with the `Category` attribute.")
        categoryNames: List<String>? = null,
        @McpDescription(description = "Regex patterns to filter tests by their full name. Matches against test fixtures, namespaces, or individual test names. Generally, specify the test class that corresponds to the modified class (same namespace, class name with `Test` appended).")
        groupNames: List<String>? = null,
        @McpDescription(description = "The full name of the tests to match the filter. This is usually in the format `Namespace.FixtureName.TestName`. If the test has test arguments, then include them in parentheses (e.g. `Namespace.FixtureName.TestName(1,2)`). Generally, specify when only a specific test is failing, or when only a limited number of tests are affected. Each name is NFC-normalized before matching, so multibyte test names (including dakuten/handakuten characters) can be specified in either NFC or NFD form.")
        testNames: List<String>? = null
    ): RunUnityTestsResult =
        runUnityTestsTool.run_unity_tests(testMode, assemblyNames, categoryNames, groupNames, testNames)

    @McpTool(name = "run_method_in_unity")
    @McpDescription(description = """
        Invoke a static method in Unity Editor via reflection. The method must be static and parameterless. The method's return value is NOT returned.
        `success` indicates only whether the method was found and invoked. Even if the method throws internally, `success` may be true.
        Console logs during the method will be captured and returned in the `logs` field of the response.

        Identify Assembly:
        1. Find the assembly definition file (.asmdef) in the parent directory hierarchy of the target file.
        2. The assembly name is the `name` property in the .asmdef file.
        3. If no .asmdef exists in the hierarchy, check the directory path: if it contains a directory named `Editor`, use `Assembly-CSharp-Editor`; otherwise use `Assembly-CSharp`.

        IMPORTANT: If you have modified any C# source files, call `get_unity_compilation_result` first to trigger a refresh and verify compilation succeeds before invoking this tool.

        IMPORTANT: This tool blocks until the method returns — do not call it again while waiting. Async methods invoked here are not awaited; logs generated after the method returns will not appear in the response.
    """)
    suspend fun run_method_in_unity(
        @McpDescription(description = "Assembly name containing the type (e.g., 'Assembly-CSharp-Editor')")
        assemblyName: String,
        @McpDescription(description = "Fully qualified type name (e.g., 'MyNamespace.MyEditorTool')")
        typeName: String,
        @McpDescription(description = "Static method name to invoke (e.g., 'DoSomething')")
        methodName: String
    ): RunMethodInUnityResult =
        runMethodInUnityTool.run_method_in_unity(assemblyName, typeName, methodName)

    @McpTool(name = "unity_play_control")
    @McpDescription(description = """
        Control Unity Editor's play mode.

        Actions:
        - `play`: Enter play mode.
        - `stop`: Exit play mode. IMPORTANT: Must stop play mode before calling `get_unity_compilation_result`.
        - `pause`: Pause at the current frame while in play mode.
        - `resume`: Resume from paused state.
        - `step`: Advance exactly one frame while paused.
        - `status`: Read-only query. Returns current `isPlaying` and `isPaused` state without changing anything.
    """)
    suspend fun unity_play_control(
        @McpDescription(description = "Action to perform: `play`, `stop`, `pause`, `resume`, `step`, or `status` (case insensitive)")
        action: String
    ): PlayControlResult =
        playControlTool.unity_play_control(action)
}
