# E2E test cases

E2E test cases for the four tools provided by this plugin.

To run these tests, open a Unity project in Unity Editor and Rider, then instruct any coding agent to execute the steps below.

If an unexpected error occurs, stop immediately (without retrying) and report the error to the human.

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
2. Wait for Unity to detect the compilation error
3. Run `get_unity_compilation_result` (no parameters)
4. Verify: `success=false`, `logs` contains error info (`type="Error"`, `message` contains the error message)
5. Remove the added code and wait for Unity to finish compilation

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

---

## Notes: Retry Rules

For the following tool calls, if the expected result is not obtained, **wait 5 seconds and retry** up to 10 times.

- `get_unity_compilation_result`: May temporarily fail when called before Unity finishes compilation
- `unity_play_control` (`action="status"` only): Unity Editor state may take time to reflect immediately after play/stop
