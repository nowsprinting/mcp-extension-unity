# IntelliJ MCP Server Extension for Unity Editor

A plugin that extends the [MCP Server](https://plugins.jetbrains.com/plugin/26071-mcp-server) built into JetBrains Rider.
Adds tools for operating Unity Editor from Coding Agents.

## Features

- **No per-project MCP server package required** — no need to install an MCP server package into each Unity project.
- **Zero agent configuration** — if the MCP Server is already enabled in Rider, Coding Agents can use the tools immediately with no additional setup.

## Requirements

- JetBrains Rider 2025.3+

## Provided Tools

### `get_unity_compilation_result`

Triggers Unity's `AssetDatabase.Refresh()` and checks whether compilation succeeded.
Useful for verifying that code changes compile before running tests.

**Parameters**: none

**Response**

| Field | Type | Description |
|---|---|---|
| `success` | boolean | `true` if compilation succeeded |
| `errorMessage` | string | Error details (only present when `success` is `false`) |

---

### `run_unity_tests`

Runs tests on Unity Test Runner.
It is recommended to filter by `assemblyNames`, `categoryNames`, `groupNames`, or `testNames` to narrow down the tests to the scope of changes.

> [!TIP]  
> Recommended to use with the [Agent Skill Example](#agent-skill-example) described below.

**Parameters**

| Name | Required | Description |
|---|---|---|
| `testMode` | **Required** | `EditMode` or `PlayMode` (case insensitive). If the `includePlatforms` in the assembly definition file (`.asmdef`) contains `Editor`, it is an Edit Mode test; otherwise it is a Play Mode test. |
| `assemblyNames` | **Required** | Names of assemblies to include (without `.dll` extension, e.g. `MyFeature.Tests`). Use the `name` property in the assembly definition file. Find them in `.asmdef` files or Rider's Unit Test Explorer. |
| `categoryNames` | Optional | Category names to include in the run. |
| `groupNames` | Optional | Group names supporting Regex (e.g. `^MyNamespace\\.`). Useful for running specific fixtures or namespaces. |
| `testNames` | Optional | Full test names to match (e.g. `MyTestClass2.MyTestWithMultipleValues(1)`). |

**Response**

| Field | Type | Description |
|---|---|---|
| `success` | boolean | `true` if all tests passed |
| `passCount` | number | Number of passing tests |
| `skipCount` | number | Number of skipped tests |
| `failCount` | number | Number of failing tests |
| `inconclusiveCount` | number | Number of inconclusive tests |
| `failedTests` | array | Details of failed tests (`testId`, `output`, `duration`) |
| `inconclusiveTests` | array | Details of inconclusive tests (`testId`, `output`, `duration`) |
| `errorMessage` | string | Error details (only present when the tool itself failed) |

## Architecture

```
Coding Agent (e.g., Claude Code)
    ↓ MCP (HTTP/SSE)
JetBrains MCP Server (built into Rider 2025.3+)
    ↓ extension point (com.intellij.mcpServer)
[This Plugin — Kotlin Frontend]   ← RunUnityTestsToolset.kt
    ↓ UnityTestMcpModel (custom Rd: IRdCall<McpRunTestsRequest, McpRunTestsResponse>)
[Plugin Backend — C# / UnityTestMcpHandler]
    ↓ BackendUnityModel.UnitTestLaunch + RunUnitTestLaunch (existing Rd)
Unity Editor
    ↓ TestRunnerApi.Execute()
Test execution (results via TestResult/RunResult signals)
```

Rider uses two separate [Reactive Distributed (Rd)](https://github.com/JetBrains/rd) protocol connections:

- **Kotlin Frontend ↔ C# Backend**: `FrontendBackendModel`
- **C# Backend ↔ Unity Editor**: `BackendUnityModel`

A custom Rd model (`UnityTestMcpModel`) bridges the two layers, since the Kotlin Frontend cannot directly access `BackendUnityModel`.

## Installation

1. Download the plugin ZIP from the [Releases](../../releases) page.
2. Open Rider and go to **Settings > Plugins**.
3. Click the gear icon and select **Install Plugin from Disk...**.
4. Select the downloaded ZIP file and restart Rider.

## Configuration

If the MCP Server is already enabled in Rider, no additional configuration is required.

If it is not yet enabled:

1. Open **Settings > Tools > MCP Server**.
2. Enable the MCP Server.

> [!NOTE]  
> See the [MCP Server](https://www.jetbrains.com/help/rider/mcp-server.html) for more details on configuration and usage.

## Agent Skill Example

To use the `/run-tests` skill in Claude Code, create a skill file with the following content:

```markdown
---
name: run-tests
description: Run tests on Unity editor using the run_unity_tests tool.
---

Please run the tests on Unity editor with `run_unity_tests` tool.

## Identify the assembly

1. First, identify the assembly definition file (.asmdef) located in the parent directory hierarchy of the run target file.
2. The assembly name can be obtained from the `name` property in the assembly definition.
3. If the `includePlatforms` in the assembly definition contains `Editor`, it is an Edit Mode test; otherwise, it is a Play Mode test.

## Specify filters

The filters are determined in the following order to minimize the number of tests performed:

1. **testNames**: Specify when only a specific test is failing, or when only a limited number of tests are affected.
2. **groupNames**: Specify the test class that is the counterpart of the modified class. The namespace is the same as the modified class, the class name with "Test" appended.
3. **categoryNames**: Specify the category name if the test class/ method is decorated with the `Category` attribute.

## Run tests

Use the `run_unity_tests` tool to run the tests.
Specify the test mode, test assembly name, and filters as parameters to the tool.
```
