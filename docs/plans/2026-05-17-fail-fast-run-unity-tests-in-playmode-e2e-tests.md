# E2E Tests: run_unity_tests Fail-fast in PlayMode

Verify that calling `run_unity_tests` while Unity Editor is in PlayMode returns an immediate error instead of blocking until the MCP client times out.

Follow the setup steps in the "0. SetUp" section of `docs/e2e-tests.md`.
Also see test cases 1-12 and 1-13 in `docs/e2e-tests.md`.

---

## 1. Returns a connection timeout error (not a PlayMode error) when Editor is not connected to Rider

1. Quit Unity Editor
2. Run `run_unity_tests` with `assemblyNames=["McpExtensionUnity.Tests"]`, `testMode="PlayMode"`
3. Verify: `success=false` and `errorMessage` contains a connection timeout message including "30 seconds" (not a PlayMode-related message)
4. Restart Unity Editor to restore the session
