# E2E Tests: Fix get_unity_compilation_result race condition

E2E test cases specific to the race-condition fix in `get_unity_compilation_result`.

Refer to `docs/e2e-tests.md` for setup, teardown, and retry rules.

---

## 1. Compilation error returned immediately (modified 2-2)

1. Run E2E setup (section 0 of `docs/e2e-tests.md`) if not already done
2. Add the following code to `McpExtensionUnityTest.cs`
   ```csharp
   private void CompileError()
   {
       THIS_DOES_NOT_EXIST();
   }
   ```
3. Run `get_unity_compilation_result` immediately — do **not** wait for Unity to finish compilation
4. Verify: `success=false`, `logs` contains `type="Error"` with the compilation error message
5. Remove the added code and wait for Unity to finish compilation

---

## 2. Rapid successive calls during compilation

1. Edit `McpExtensionUnityTest.cs` to introduce any code change (e.g., add a blank line)
2. Call `get_unity_compilation_result` twice back-to-back without any delay between calls
3. Verify:
   - Neither call produces `"Unity Editor did not connect within 30 seconds"`
   - The second call either returns `success=true` or `"Unity is currently compiling or reloading assemblies. Wait a few seconds and retry get_unity_compilation_result."`
   - No call hangs for more than 2 minutes

---

## Notes

- These cases exercise the `WaitForModelReconnect` code path in `UnityCompilationMcpHandler`.
- The C# backend log at `~/Library/Logs/JetBrains/Rider*/backend.*.log` should show:
  - `Refresh threw (likely domain reload): ...` followed by a measurable delay before `Unity model available`
  - No `GetCompilationResult failed: The operation was canceled.` in normal flow after the fix
