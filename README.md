# IntelliJ MCP Server Extension for Unity

A plugin that extends the [MCP Server plugin](https://plugins.jetbrains.com/plugin/26071-mcp-server) built into JetBrains Rider.
Adds tools for operating Unity Editor from any Coding Agents.

## Features

- **No per-project MCP server package required** — no need to install an MCP server package into each Unity project.
- **Zero agent configuration** — if the MCP Server is already enabled in Rider, Coding Agents can use the tools immediately with no additional setup.
- **Multiple tools for Unity Editor** — currently provides `run_unity_tests`, `get_unity_compilation_result`, and `unity_play_control`, with more tools planned in the future.

## Requirements

- JetBrains Rider 2025.3+

## Provided Tools

### `run_unity_tests`

Runs tests on Unity Test Runner.
It is recommended to filter by `assemblyNames`, `categoryNames`, `groupNames`, and `testNames` to narrow down the tests to the scope of changes.

> [!TIP]  
> Recommended to use with the Agent Skills, see [example](#agent-skill-example).

**Parameters**

| Name            | Required     | Description                                                                                                                                                                                      |
|-----------------|--------------|--------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------|
| `testMode`      | **Required** | `EditMode` or `PlayMode` (case insensitive). If the `includePlatforms` in the assembly definition file (`.asmdef`) contains `Editor`, it is an Edit Mode test; otherwise it is a Play Mode test. |
| `assemblyNames` | **Required** | Names of test assemblies to run (without `.dll` extension, e.g. `MyFeature.Tests`). Specify the `name` property in the assembly definition file.                                                 |
| `categoryNames` | Optional     | Names of a category to include in the run. Any test or fixture runs that have a category matching the string.                                                                                    |
| `groupNames`    | Optional     | Same as testNames, except that it allows for Regex. This is useful for running specific fixtures or namespaces.                                                                                  |
| `testNames`     | Optional     | The full name of the tests to match the filter. This is usually in the format FixtureName.TestName. If the test has test arguments, then include them in parentheses.                            |

**Response**

| Field               | Type    | Description                                                    |
|---------------------|---------|----------------------------------------------------------------|
| `success`           | boolean | `true` if all tests passed                                     |
| `passCount`         | number  | Number of passing tests                                        |
| `skipCount`         | number  | Number of skipped tests                                        |
| `failCount`         | number  | Number of failing tests                                        |
| `inconclusiveCount` | number  | Number of inconclusive tests                                   |
| `failedTests`       | array   | Details of failed tests (`testId`, `output`, `duration`)       |
| `inconclusiveTests` | array   | Details of inconclusive tests (`testId`, `output`, `duration`) |
| `errorMessage`      | string  | Error details (only present when the tool itself failed)       |

### `get_unity_compilation_result`

Triggers Unity's `AssetDatabase.Refresh()` and checks whether compilation succeeded.
Useful for verifying that code changes compile before running tests.

**Parameters**: none

**Response**

| Field          | Type    | Description                                            |
|----------------|---------|--------------------------------------------------------|
| `success`      | boolean | `true` if compilation succeeded                        |
| `errorMessage` | string  | Error details (only present when `success` is `false`) |

### `unity_play_control`

Controls Unity Editor's play mode. Requires Unity Editor to be connected to Rider.

**Parameters**

| Name     | Required     | Description                                                                                  |
|----------|--------------|----------------------------------------------------------------------------------------------|
| `action` | **Required** | Action to perform: `play`, `stop`, `pause`, `resume`, `step`, or `status` (case insensitive) |

| Action   | Operation                                         |
|----------|---------------------------------------------------|
| `play`   | Enter play mode                                   |
| `stop`   | Exit play mode                                    |
| `pause`  | Pause while in play mode                          |
| `resume` | Resume from paused state                          |
| `step`   | Advance one frame (enters paused play mode)       |
| `status` | Read current play/pause state without any changes |

**Response**

| Field          | Type    | Description                                                                    |
|----------------|---------|--------------------------------------------------------------------------------|
| `success`      | boolean | `true` if the action was performed successfully                                |
| `action`       | string  | The action that was performed (only when `success` is `true`)                  |
| `isPlaying`    | boolean | Whether Unity Editor is currently in play mode (only when `success` is `true`) |
| `isPaused`     | boolean | Whether Unity Editor is currently paused (only when `success` is `true`)       |
| `errorMessage` | string  | Error details (only present when `success` is `false`)                         |

## Architecture

```
Coding Agent (e.g., Claude Code)
    ↓ MCP (HTTP/SSE)
JetBrains MCP Server (built into Rider 2025.3+)
    ↓ extension point (com.intellij.mcpServer)
[This Plugin — Kotlin Frontend]
    ├── RunUnityTestsToolset.kt / PlayControlToolset.kt
    │       ↓ (unity_play_control, get_unity_compilation_result)
    │   FrontendBackendModel.playControls / UnityTestMcpModel.getCompilationResult
    │       ↓ (run_unity_tests)
    │   UnityTestMcpModel (custom Rd: IRdCall<McpRunTestsRequest, McpRunTestsResponse>)
    │       ↓
[Plugin Backend — C# / UnityTestMcpHandler]
    ↓ BackendUnityModel.UnitTestLaunch + RunUnitTestLaunch (existing Rd)
Unity Editor
    ↓ TestRunnerApi.Execute()
Test execution (results via TestResult/RunResult signals)
```

Rider uses two separate [Reactive Distributed (Rd)](https://github.com/JetBrains/rd) protocol connections:

- **Kotlin Frontend ↔ C# Backend**: `FrontendBackendModel`
- **C# Backend ↔ Unity Editor**: `BackendUnityModel`

`unity_play_control` accesses `FrontendBackendModel.playControls` directly from Kotlin, requiring no C# backend changes.
`run_unity_tests` uses a custom Rd model (`UnityTestMcpModel`) to bridge the two layers, since the Kotlin Frontend cannot directly access `BackendUnityModel`.

## Installation

1. Download the plugin ZIP from the [Releases](../../releases) page.
2. Open Rider and go to **Settings > Plugins**.
3. Click the gear icon and select **Install Plugin from Disk...**.
4. Select the downloaded ZIP file and restart Rider.

## Configuration

If the MCP Server is already enabled in Rider, no additional configuration is required.

If it is not yet enabled:

1. Open **Settings > Tools > MCP Server**.
2. Click **Enable MCP Server**.
3. Click **Auto-Configure** for the agent you want to use.

> [!NOTE]
> See the [MCP Server](https://www.jetbrains.com/help/rider/mcp-server.html) for more details on configuration and usage.

### Environment Variables

| Variable           | Default | Description                                                                                                                                                 |
|--------------------|---------|-------------------------------------------------------------------------------------------------------------------------------------------------------------|
| `MCP_TOOL_TIMEOUT` | `300`   | Timeout in seconds for `run_unity_tests`. Set a smaller value to get faster feedback when Unity Test Runner cancellation does not fire a completion signal. |

## Agent Skill Example

To use the `/run-tests` skill in Claude Code, create a skill file with the following content:

```markdown
---
name: run-tests
description: Run tests on Unity editor using the run_unity_tests tool.
---

Please run the tests on Unity editor with `run_unity_tests` tool.

## Identify the Assembly

Identify the test assembly name and test mode to run.

1. First, identify the assembly definition file (.asmdef) located in the parent directory hierarchy of the run target file.
2. The assembly name can be obtained from the `name` property in the assembly definition.
3. If the `includePlatforms` in the assembly definition contains `Editor`, it is an Edit Mode test; otherwise, it is a Play Mode test.

## Specify Filters

It is recommended to specify the following filter to minimize the number of tests that are run:

### assemblyNames

The name of test assemblies to run. That is the assembly file name, without `.dll` extension.
e.g., `MyFeature.Tests`

### categoryNames

The names of a category to include in the run. Any test or fixture runs that have a category matching the string.

Specify the category name if the test class/method is decorated with the `Category` attribute.

### groupNames

Same as testNames, except that it allows for Regex. This is useful for running specific fixtures or namespaces.

Generally, specify the test class that corresponds to the modified class. The namespace is the same as the modified class, the class name with `Test` appended.

### testNames

The full name of the tests to match the filter. This is usually in the format `FixtureName.TestName`. If the test has test arguments, include them in parentheses.
e.g., `FixtureName.TestName(1,2)`

Generally, specify when only a specific test is failing, or when only a limited number of tests are affected.

## Run Tests

Use the `run_unity_tests` tool to run the tests.
Specify the test mode, test assembly name, and filters as parameters to the tool.

## Rules for Test Failures

If the same test(s) fail on two or more consecutive runs, stop and consult the user rather than continuing to fix.

When consulting, clarify:

- Current failure status: what is failing and the likely cause
- Fix history: what was changed, how many times, and the scope of impact
- Planned approach: what options are being considered next

## Troubleshooting

When a tool fails with a connection error, it may be due to the following reasons:

- The connection may have been disconnected due to domain reloading caused by compilation, etc. Wait a moment and try again.
- Play Mode tests cannot be run if there are any compilation errors. Check for any compilation errors using the `get_unity_compilation_result` and `get_file_problems` tool.
- The test may be timing out due to a long execution time. Review the filter settings to narrow down the tests to be executed, or ask the user to extend the timeout setting.
```

See full example: [nowsprinting/claude-code-settings-for-unity](https://github.com/nowsprinting/claude-code-settings-for-unity).
