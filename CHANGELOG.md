# Changelog

All notable changes to this project will be documented in this file.
The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.1.0/),
and this project adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

## [Unreleased]

## [1.0.8] - 2026-07-22

### Changed

- Migrate to Rider 2026.2 (build 262); Rider 2026.2 removed the Rd protocol APIs this plugin relied on, and the replacement APIs are binary-incompatible with earlier Rider versions, so a single build cannot support both. Continue using v1.0.7 on Rider 2026.1.x and earlier.

## [1.0.7] - 2026-06-24

### Fixed

- Fix `run_unity_tests` silently failing to match test names containing dakuten/handakuten characters (e.g., `で`, `が`, `パ`) when the `testNames` input is in Unicode NFD form. `testNames` entries are now NFC-normalized before being passed to Unity's `FullNameFilter`.

## [1.0.6] - 2026-06-11

### Changed

- Change `MCP_TOOL_TIMEOUT` default to 100,000,000 ms (same as Claude Code's default)
- Require IDE restart after plugin installation

### Fixed

- Fix `run_unity_tests` hanging until the MCP SDK transport timeout and dropping the transport when the domain-reload reconnect handler encountered the transient `BackendUnityModel` (old port) that Unity briefly advertises before the stable post-reload connection arrives.
- Fix `run_unity_tests` creating a phantom second test session when Unity reconnects after a domain reload during PlayMode test execution.
- Fix `run_unity_tests` hanging until the MCP transport timeout when called after `get_unity_compilation_result` following cumulative PlayMode domain reloads.
- Fix `run_unity_tests` faulting the Rd handler (and potentially dropping the MCP transport) when the initial `BackendUnityModel` from `WaitForUnityModel` is transient.
- Fix `run_method_in_unity` and `unity_play_control` silently failing when Unity Editor is not connected at call time.
- Fix `get_unity_compilation_result` returning `success=true` before Unity finishes loading the newly compiled assemblies.
- Fix `get_unity_compilation_result` requiring repeated calls when `GetCompilationResult.Start` throws a non-`OperationCanceledException` Rd exception on a transient `BackendUnityModel`.
- Fix all four MCP tools treating `CancellationException` as an ordinary error when the MCP SDK cancels the tool call (e.g., on client-side timeout).

## [1.0.5] - 2026-05-19

### Changed

- Fail-fast in `run_unity_tests` when Unity Editor is in PlayMode, instead of blocking until the MCP client times out
- Mark required parameters as required in the MCP tool schema
- Add troubleshooting tips to tool descriptions and error messages
- Limit supported Rider version to 2026.1.x (`until-build = 261.*`); Rider 2026.2 introduces breaking plugin API changes

### Fixed

- Fix race condition in `get_unity_compilation_result` when called during Unity compilation or domain reload

## [1.0.3] - 2026-04-12

### Changed

- Merge all four MCP tools under `UnityEditorToolset`

## [1.0.2] - 2026-03-10

### Changed

- Enrich tool descriptions with pre-execution workflow guidance
- Enrich tool descriptions to work without agent skills

## [1.0.1] - 2026-02-28

### Fixed

- Fix domain-reload reconnection handling

## [1.0.0] - 2026-02-26

### Initial Release

- `run_unity_tests` tool
- `run_method_in_unity` tool
- `get_unity_compilation_result` tool
- `unity_play_control` tool

[Unreleased]: https://github.com/nowsprinting/mcp-extension-unity/compare/v1.0.8...HEAD
[1.0.8]: https://github.com/nowsprinting/mcp-extension-unity/compare/v1.0.7...v1.0.8
[1.0.7]: https://github.com/nowsprinting/mcp-extension-unity/compare/v1.0.6...v1.0.7
[1.0.6]: https://github.com/nowsprinting/mcp-extension-unity/compare/v1.0.5...v1.0.6
[1.0.5]: https://github.com/nowsprinting/mcp-extension-unity/compare/v1.0.3...v1.0.5
[1.0.4]: https://github.com/nowsprinting/mcp-extension-unity/compare/v1.0.3...v1.0.4
[1.0.3]: https://github.com/nowsprinting/mcp-extension-unity/compare/v1.0.2...v1.0.3
[1.0.2]: https://github.com/nowsprinting/mcp-extension-unity/compare/v1.0.1...v1.0.2
[1.0.1]: https://github.com/nowsprinting/mcp-extension-unity/compare/v1.0.0...v1.0.1
[1.0.0]: https://github.com/nowsprinting/mcp-extension-unity/commits/v1.0.0
