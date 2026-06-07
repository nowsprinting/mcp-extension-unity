# E2E test cases

E2E test cases for the four tools provided by this plugin.

To run these tests, open a Unity project in Unity Editor and Rider, then instruct any coding agent to execute the steps below.

## Rules

- **Do not load any skills.** These tests verify the raw behavior of the MCP tools. Execute using plain MCP tool calls only, without loading agent skills (custom workflows, auto-retry logic, etc.).
- **If an unexpected error occurs, stop immediately** (without retrying) and report the error to the human.
- **If a tool call has not responded within 90 seconds, stop immediately** and report to the human. Normal operations (domain reload + test execution) complete well within 60 seconds; a 90-second silence indicates a hang.
- For the following tool calls, if the expected result is not obtained, **wait 5 seconds and retry** up to 5 times:
  - `get_unity_compilation_result`: If Unity is still compiling, the response may contain `"Unity is currently compiling or reloading assemblies"` — wait 5 seconds and retry. Note: after a successful compilation, the tool now waits internally for the post-compile domain reload to settle before returning, so a subsequent tool call should normally succeed on the first attempt.
  - `unity_play_control` (`action="status"` only): Unity Editor state may take time to reflect immediately after play/stop.

## 0. SetUp

1. Create the `Assets/McpExtensionUnity/Tests/` directory
2. Create `Assets/McpExtensionUnity/Tests/McpExtensionUnity.Tests.asmdef`
   ```json
   {
       "name": "McpExtensionUnity.Tests",
       "optionalUnityReferences": [
           "TestAssemblies"
       ]
   }
   ```
3. Create `Assets/McpExtensionUnity/Tests/McpExtensionUnityTest.cs`
   ```csharp
   using NUnit.Framework;

   namespace McpExtensionUnity.Tests
   {
       [TestFixture]
       public class McpExtensionUnityTest
       {
       }
   }
   ```
4. Create the `Assets/McpExtensionUnity/Editor/` directory
5. Create `Assets/McpExtensionUnity/Editor/McpExtensionUnity.Editor.asmdef`
   ```json
   {
       "name": "McpExtensionUnity.Editor",
       "includePlatforms": [
           "Editor"
       ]
   }
   ```
6. Create `Assets/McpExtensionUnity/Editor/McpExtensionUnityEditorScript.cs`
   ```csharp
   namespace McpExtensionUnity.Editor
   {
       public static class McpExtensionUnityEditorScript
       {
       }
   }
   ```
7. Wait for Unity to finish compilation

---

## 1. `run_unity_tests`

All test cases are run in PlayMode (`testMode="PlayMode"`).

### 1-1. Passing test

1. Add the following test method to `McpExtensionUnityTest.cs`
   ```csharp
   [Test]
   public void RunUnityTests_Success()
   {
   }
   ```
2. Run `run_unity_tests` with `assemblyNames=["McpExtensionUnity.Tests"]`, `testMode="PlayMode"`
3. Verify: `success=true`, `passCount=1`, `failCount=0`
4. Remove the added method

### 1-2. Failing test

1. Add the following test method to `McpExtensionUnityTest.cs`
   ```csharp
   [Test]
   public void RunUnityTests_Fail()
   {
       Assert.Fail("intentional failure");
   }
   ```
2. Run `run_unity_tests` with `assemblyNames=["McpExtensionUnity.Tests"]`, `testMode="PlayMode"`
3. Verify: `success=false`, `passCount=0`, `failCount=1`, `failedTests` contains test details (`testId`, `output`)
4. Remove the added method

### 1-3. Inconclusive only

1. Add the following test method to `McpExtensionUnityTest.cs`
   ```csharp
   [Test]
   public void RunUnityTests_Inconclusive()
   {
       Assert.Inconclusive("inconclusive");
   }
   ```
2. Run `run_unity_tests` with `assemblyNames=["McpExtensionUnity.Tests"]`, `testMode="PlayMode"`
3. Verify: `success=false`, `inconclusiveCount=1`, `inconclusiveTests` contains test details
4. Remove the added method

### 1-4. Skipped only

1. Add the following test method to `McpExtensionUnityTest.cs`
   ```csharp
   [Test]
   [Ignore("intentional skip")]
   public void RunUnityTests_Skip()
   {
   }
   ```
2. Run `run_unity_tests` with `assemblyNames=["McpExtensionUnity.Tests"]`, `testMode="PlayMode"`
3. Verify: `success=false`, `skipCount=1`
4. Remove the added method

### 1-5. Passing + skipped

1. Add the following test methods to `McpExtensionUnityTest.cs`
   ```csharp
   [Test]
   public void RunUnityTests_Pass()
   {
   }

   [Test]
   [Ignore("intentional skip")]
   public void RunUnityTests_Skip()
   {
   }
   ```
2. Run `run_unity_tests` with `assemblyNames=["McpExtensionUnity.Tests"]`, `testMode="PlayMode"`
3. Verify: `success=true`, `passCount=1`, `skipCount=1`
4. Remove the added methods

### 1-6. Filter by testNames

1. Add the following test methods to `McpExtensionUnityTest.cs`
   ```csharp
   [Test]
   public void RunUnityTests_A()
   {
   }

   [Test]
   public void RunUnityTests_B()
   {
   }
   ```
2. Run `run_unity_tests` with `assemblyNames=["McpExtensionUnity.Tests"]`, `testMode="PlayMode"`, `testNames=["McpExtensionUnity.Tests.McpExtensionUnityTest.RunUnityTests_A"]`
3. Verify: `passCount=1` (`RunUnityTests_B` is not executed)
4. Remove the added methods

### 1-7. Filter by categoryNames

1. Add the following test methods to `McpExtensionUnityTest.cs`
   ```csharp
   [Test]
   [Category("Foo")]
   public void RunUnityTests_Foo()
   {
   }

   [Test]
   public void RunUnityTests_NoCategory()
   {
   }
   ```
2. Run `run_unity_tests` with `assemblyNames=["McpExtensionUnity.Tests"]`, `testMode="PlayMode"`, `categoryNames=["Foo"]`
3. Verify: `passCount=1` (`RunUnityTests_NoCategory` is not executed)
4. Remove the added methods

### 1-8. Filter by groupNames (regex)

1. Add the following test methods to `McpExtensionUnityTest.cs`
   ```csharp
   [Test]
   public void RunUnityTests_Target()
   {
   }

   [Test]
   public void AnotherTest_NotTarget()
   {
   }
   ```
2. Run `run_unity_tests` with `assemblyNames=["McpExtensionUnity.Tests"]`, `testMode="PlayMode"`, `groupNames=["^McpExtensionUnity\\.Tests\\.McpExtensionUnityTest\\.RunUnityTests_"]`
3. Verify: `passCount=1` (`AnotherTest_NotTarget` is not executed)
4. Remove the added methods

### 1-9. Missing assemblyNames

1. Run `run_unity_tests` with `testMode="PlayMode"` only (no `assemblyNames`)
2. Verify: `success=false`, `errorMessage` indicates that `assemblyNames` is required (Unity Editor is not reached)

### 1-10. Missing testMode

1. Run `run_unity_tests` with `assemblyNames=["McpExtensionUnity.Tests"]` only (no `testMode`)
2. Verify: `success=false`, `errorMessage` indicates that `testMode` is required

### 1-11. Invalid testMode

1. Run `run_unity_tests` with `assemblyNames=["McpExtensionUnity.Tests"]`, `testMode="Invalid"`
2. Verify: `success=false`, `errorMessage` indicates that the `testMode` value is invalid

### 1-12. Fail-fast when Editor is in PlayMode

1. Run `unity_play_control` with `action="play"` to enter PlayMode
2. Run `run_unity_tests` with `assemblyNames=["McpExtensionUnity.Tests"]`, `testMode="PlayMode"`
3. Verify: `success=false` is returned within ~1 second, and `errorMessage` contains a message instructing to call `unity_play_control` with `action='stop'`

### 1-13. Runs normally after stopping PlayMode

(Continuing from 1-12, starting from a stopped state)

1. Run `unity_play_control` with `action="stop"`
2. Add the following test method to `McpExtensionUnityTest.cs`
   ```csharp
   [Test]
   public void RunUnityTests_Success()
   {
   }
   ```
3. Run `run_unity_tests` with `assemblyNames=["McpExtensionUnity.Tests"]`, `testMode="PlayMode"`
4. Verify: `success=true`, `passCount=1`
5. Remove the added method

### 1-14. Called immediately after .cs edit without prior compilation check (regression test)

Regression test for the transient `BackendUnityModel` reconnect race condition during PlayMode test execution.
**Pre-fix symptom**: `run_unity_tests` hung for ~180 seconds and the MCP transport was dropped
(`"MCP server 'jetbrains' transport dropped mid-call; response for tool 'run_unity_tests' was lost"`).

1. Add the following test method to `McpExtensionUnityTest.cs`
   ```csharp
   [Test]
   public void RunUnityTests_AfterEdit()
   {
   }
   ```
2. **Immediately** (without waiting for domain reload and without calling `get_unity_compilation_result`) run `run_unity_tests` with `assemblyNames=["McpExtensionUnity.Tests"]`, `testMode="PlayMode"`
3. Verify: `success=true`, `passCount≥1`, **no** transport drop and no timeout
4. Remove the added method

---

## 2. `get_unity_compilation_result`

### 2-1. Compilation success

1. Run `get_unity_compilation_result` (no parameters)
2. Verify: `success=true`

### 2-2. Compilation error (including log collection)

1. Add the following code to `McpExtensionUnityTest.cs` to intentionally cause a compilation error
   ```csharp
   private void CompileError()
   {
       THIS_DOES_NOT_EXIST();
   }
   ```
2. Run `get_unity_compilation_result` immediately (no parameters) — do **not** wait for Unity to finish compilation
3. Verify: `success=false`, `logs` contains error info (`type="Error"`, `message` contains the error message)
4. Remove the added code and wait for Unity to finish compilation

### 2-3. Rapid successive calls during compilation

1. Edit `McpExtensionUnityTest.cs` to introduce any code change (e.g., add a blank line)
2. Call `get_unity_compilation_result` twice back-to-back without any delay between calls
3. Verify:
   - Neither call produces `"Unity Editor did not connect within 30 seconds"`
   - The second call either returns `success=true` or `"Unity is currently compiling or reloading assemblies. Wait a few seconds and retry get_unity_compilation_result."`
   - No call hangs for more than 2 minutes

---

## 3. `unity_play_control`

### 3-1. Play and check status

1. Run `unity_play_control` with `action="play"`
2. Verify: `success=true`, `action="play"`, `isPlaying=true`
3. Run `unity_play_control` with `action="status"`
4. Verify: `success=true`, `isPlaying=true`
5. Run `unity_play_control` with `action="stop"` to exit Play Mode

### 3-2. Stop and check status

(Run after 3-1, starting from a stopped state)

1. Run `unity_play_control` with `action="stop"`
2. Verify: `success=true`, `action="stop"`, `isPlaying=false`
3. Run `unity_play_control` with `action="status"`
4. Verify: `success=true`, `isPlaying=false`

### 3-3. Invalid action

1. Run `unity_play_control` with `action="invalid"`
2. Verify: `success=false`, `errorMessage` indicates that the `action` value is invalid

---

## 4. `run_method_in_unity`

### 4-1. Successful static method execution

1. Add the following method to `McpExtensionUnityEditorScript.cs`
   ```csharp
   public static void DoNothing()
   {
   }
   ```
2. Run `run_method_in_unity` with `assemblyName="McpExtensionUnity.Editor"`, `typeName="McpExtensionUnity.Editor.McpExtensionUnityEditorScript"`, `methodName="DoNothing"`
3. Verify: `success=true`
4. Remove the added method

### 4-2. Console log collection

1. Add the following method to `McpExtensionUnityEditorScript.cs`
   ```csharp
   public static void LogMessage()
   {
       UnityEngine.Debug.Log("Hello from McpExtensionUnityEditorScript");
   }
   ```
2. Run `run_method_in_unity` with `assemblyName="McpExtensionUnity.Editor"`, `typeName="McpExtensionUnity.Editor.McpExtensionUnityEditorScript"`, `methodName="LogMessage"`
3. Verify: `success=true`, `logs` contains `message="Hello from McpExtensionUnityEditorScript"`
4. Remove the added method

### 4-3. Method that throws an exception

1. Add the following method to `McpExtensionUnityEditorScript.cs`
   ```csharp
   public static void ThrowException()
   {
       throw new System.Exception("intentional exception");
   }
   ```
2. Run `run_method_in_unity` with `assemblyName="McpExtensionUnity.Editor"`, `typeName="McpExtensionUnity.Editor.McpExtensionUnityEditorScript"`, `methodName="ThrowException"`
3. Verify: `success=true` (the method invocation itself succeeds), `logs` contains the exception message (if `success=false` instead, report that)
4. Remove the added method

### 4-4. Missing parameter

1. Run `run_method_in_unity` with `assemblyName=""`, `typeName="McpExtensionUnity.Editor.McpExtensionUnityEditorScript"`, `methodName="DoNothing"` (`assemblyName` is empty string)
2. Verify: `success=false`, `errorMessage` indicates that `assemblyName` is required (Unity Editor is not reached)

### 4-5. Non-existent method

1. Run `run_method_in_unity` with `assemblyName="McpExtensionUnity.Editor"`, `typeName="McpExtensionUnity.Editor.McpExtensionUnityEditorScript"`, `methodName="NonExistentMethod"`
2. Verify: `success=false`, `errorMessage` indicates that the method was not found

---

## 5. TearDown

1. Delete the `Assets/McpExtensionUnity/` directory
2. Delete the `Assets/McpExtensionUnity.meta` file
3. Wait for Unity to finish compilation

