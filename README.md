# MCP Server Extension for Unity

[![Build](https://github.com/nowsprinting/mcp-extension-unity/actions/workflows/build.yml/badge.svg)](https://github.com/nowsprinting/mcp-extension-unity/actions/workflows/build.yml)
[![Version](https://img.shields.io/jetbrains/plugin/v/30357-mcp-server-extension-for-unity.svg)](https://plugins.jetbrains.com/plugin/30357-mcp-server-extension-for-unity)
[![Downloads](https://img.shields.io/jetbrains/plugin/d/30357-mcp-server-extension-for-unity.svg)](https://plugins.jetbrains.com/plugin/30357-mcp-server-extension-for-unity)
[![rating](https://img.shields.io/jetbrains/plugin/r/rating/30357-mcp-server-extension-for-unity.svg)](https://plugins.jetbrains.com/plugin/30357-mcp-server-extension-for-unity)

<!-- Plugin description -->
A plugin that extends the [MCP Server](https://plugins.jetbrains.com/plugin/26071-mcp-server) built into JetBrains Rider.
Adds tools for operating Unity Editor from any Coding Agents.

## Features

- **No per-project MCP server package required** — no need to install an MCP server package into each Unity project.
- **No agent configuration** — if the MCP Server is already enabled in Rider, Coding Agents can use the tools immediately with no additional setup.
- **No additional configuration required for cloned workspaces** — if you clone a workspace with `git workspace`, `claude --workspace`, etc., you get tools that work without any additional configuration.
- **Tools for Unity Editor** — provides **Run tests**, **Run method**, **Check compilation**, and **Play control**.
<!-- Plugin description end -->

## Requirements

- JetBrains Rider 2025.3+

## Provided Tools

### Run Tests

The `run_unity_tests` tool runs tests on Unity Test Runner through Rider's test infrastructure.
Recommend filtering by `assemblyNames`, `categoryNames`, `groupNames`, and `testNames` to narrow down the tests to the scope of changes.

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
| `failCount`         | number  | Number of failing tests                                        |
| `inconclusiveCount` | number  | Number of inconclusive tests                                   |
| `skipCount`         | number  | Number of skipped tests                                        |
| `failedTests`       | array   | Details of failed tests (`testId`, `output`, `duration`)       |
| `inconclusiveTests` | array   | Details of inconclusive tests (`testId`, `output`, `duration`) |

**Error Response**

| Field          | Type    | Description    |
|----------------|---------|----------------|
| `success`      | boolean | Always `false` |
| `errorMessage` | string  | Error details  |

> [!TIP]  
> Recommended to use with the Agent Skills, see [Agent Skill Examples](#agent-skill-examples).

### Run Method

The `run_method_in_unity` tool invokes a static method in Unity Editor via reflection. The method must be static and parameterless. The method's return value is NOT returned.
Console logs during the method will be captured and returned in the `logs` field of the response.

**Parameters**

| Name           | Required     | Description                                                        |
|----------------|--------------|--------------------------------------------------------------------|
| `assemblyName` | **Required** | Assembly name containing the type (e.g., `Assembly-CSharp-Editor`) |
| `typeName`     | **Required** | Fully qualified type name (e.g., `MyNamespace.MyEditorTool`)       |
| `methodName`   | **Required** | Static method name to invoke (e.g., `DoSomething`)                 |

The method must be **static and parameterless**.

**Response**

| Field     | Type    | Description                                                                                                                                             |
|-----------|---------|---------------------------------------------------------------------------------------------------------------------------------------------------------|
| `success` | boolean | Always `true` (indicates reflection succeeded; does not mean the method executed without errors — internal exceptions are captured in `logs`)           |
| `logs`    | array   | Console log entries captured during execution (may be empty). Each entry has `type` (`"Message"`, `"Warning"`, `"Error"`), `message`, and `stackTrace`. |

**Error Response**

| Field          | Type    | Description    |
|----------------|---------|----------------|
| `success`      | boolean | Always `false` |
| `errorMessage` | string  | Error details  |

> [!IMPORTANT]  
> The method's return value is **NOT** returned. `success` only indicates whether the method was found and invoked (reflection succeeded). Even if the method throws internally, `success` may be `true` — the exception is captured in the `logs` field.

> [!IMPORTANT]  
> Async methods can be invoked, but the tool does not await their completion. Logs generated after the method returns to the caller will not be included in the response.

### Check Compilation

The `get_unity_compilation_result` tool triggers Unity's `AssetDatabase.Refresh()` and checks if compilation succeeded.
Console logs during compilation will be captured and returned in the `logs` field of the response.

**Parameters**: none

**Response**

| Field     | Type    | Description                                                                                                                                               |
|-----------|---------|-----------------------------------------------------------------------------------------------------------------------------------------------------------|
| `success` | boolean | Always `true`                                                                                                                                             |
| `logs`    | array   | Console log entries captured during compilation (may be empty). Each entry has `type` (`"Message"`, `"Warning"`, `"Error"`), `message`, and `stackTrace`. |

**Error Response**

| Field          | Type    | Description                                                                                                                                             |
|----------------|---------|---------------------------------------------------------------------------------------------------------------------------------------------------------|
| `success`      | boolean | Always `false`                                                                                                                                          |
| `errorMessage` | string  | Error details                                                                                                                                           |
| `logs`         | array   | Console log entries captured before the error (may be empty). Each entry has `type` (`"Message"`, `"Warning"`, `"Error"`), `message`, and `stackTrace`. |

> [!WARNING]  
> If the Unity Editor was compiled before this tool triggered a refresh, the response will not include a log. Compilation errors will remain in the console window, but will not be available with this tool. Instead, use the `getDiagnostics` or `get_file_problems` tools, or read `editor.log`.

> [!TIP]  
> Recommended to run this tool to ensure compilation succeeds before `run_unity_tests` or `run_method_in_unity` tool if modified code.

### Play Control

The `unity_play_control` tool controls Unity Editor's play mode. Requires Unity Editor to be connected to Rider.

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

| Field       | Type    | Description                                    |
|-------------|---------|------------------------------------------------|
| `success`   | boolean | Always `true`                                  |
| `action`    | string  | The action that was performed                  |
| `isPlaying` | boolean | Whether Unity Editor is currently in play mode |
| `isPaused`  | boolean | Whether Unity Editor is currently paused       |

**Error Response**

| Field          | Type    | Description    |
|----------------|---------|----------------|
| `success`      | boolean | Always `false` |
| `errorMessage` | string  | Error details  |

## Architecture

```
Coding Agent (e.g., Claude Code)
    ↓ MCP (HTTP/SSE)
JetBrains MCP Server (built into Rider 2025.3+)
    ↓ extension point (com.intellij.mcpServer)
[This Plugin — Kotlin Frontend]
    ├── RunUnityTestsToolset.kt / PlayControlToolset.kt / RunMethodInUnityToolset.kt
    │       ↓ (unity_play_control, get_unity_compilation_result, run_method_in_unity)
    │   FrontendBackendModel.playControls / FrontendBackendModel.runMethodInUnity / UnityTestMcpModel.getCompilationResult
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
`run_method_in_unity` accesses `FrontendBackendModel.runMethodInUnity` directly from Kotlin, requiring no C# backend changes.
`run_unity_tests` uses a custom Rd model (`UnityTestMcpModel`) to bridge the two layers, since the Kotlin Frontend cannot directly access `BackendUnityModel`.

## Installation

1. Open **Settings > Plugins**.
2. Select **Marketplace** and search for "mcp unity".
3. Click **Install** on the "MCP Server Extension for Unity" plugin.

## Configuration

If the built-in **MCP Server** is already enabled in Rider, no additional configuration is required.

If it is not yet enabled:

1. Open **Settings > Tools > MCP Server**.
2. Click **Enable MCP Server**.
3. Click **Auto-Configure** for the agent you want to use.

> [!NOTE]
> See the [MCP Server](https://www.jetbrains.com/help/rider/mcp-server.html) documentation for more details on configuration and usage.

### Environment Variables

| Variable           | Default | Description                                                                                                                                                                                                            |
|--------------------|---------|------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------|
| `MCP_TOOL_TIMEOUT` | `300`   | Timeout in seconds for `run_unity_tests`, `get_unity_compilation_result`, and `run_method_in_unity`. Set a smaller value to get faster feedback when Unity Test Runner cancellation does not fire a completion signal. |

## Agent Skill Examples

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

## Troubleshooting

When a tool fails with a connection error, it may be due to the following reasons:

- The connection may have been disconnected due to domain reloading caused by compilation, etc. Wait a moment and try again.
- Play Mode tests cannot be run if there are any compilation errors. Check for any compilation errors using the `get_unity_compilation_result` and `get_file_problems` tool.
- The test may be timing out due to a long execution time. Review the filter settings to narrow down the tests to be executed, or ask the user to extend the timeout setting.
```

See full example: [nowsprinting/claude-code-settings-for-unity](https://github.com/nowsprinting/claude-code-settings-for-unity).

## FAQ

**Does it only work in the terminal window inside Rider?**

No. As long as the Rider process connected to Unity Editor is running, you can use the tools from any coding agent launched in any external terminal.

**Can I collect Unity console logs?**

No. The only API for retrieving Unity console logs is streaming-based, and MCP tools cannot return streaming responses. While buffering is technically possible, it would be inaccurate and misleading, so this is intentionally not provided. Read `editor.log` instead.

## Contributing

Contributions are welcome. However, the scope is limited to features that use Rider's `BackendUnityModel`. This plugin does not aim to be an all-in-one Unity toolbox.

> [!NOTE]  
> This project will be closed once JetBrains releases an official MCP extension for Unity.
