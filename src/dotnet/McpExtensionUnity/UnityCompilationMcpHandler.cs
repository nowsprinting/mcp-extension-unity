// All resharper-unity APIs used in this file (BackendUnityHost, BackendUnityModel, RefreshType)
// are sourced from the Apache 2.0 open-source resharper-unity plugin:
//   https://github.com/JetBrains/resharper-unity

using System;
using System.Threading.Tasks;
using JetBrains.Application.Components;
using JetBrains.Application.Parts;
using JetBrains.Core;
using JetBrains.Util;
using JetBrains.Util.Logging;
using JetBrains.Lifetimes;
using JetBrains.ProjectModel;
using JetBrains.Rd;
using JetBrains.Rd.Tasks;
using JetBrains.Rider.Model.Unity.BackendUnity;
using JetBrains.ReSharper.Plugins.Unity.Rider.Integration.Protocol;
using McpExtensionUnity.Model;

namespace McpExtensionUnity
{
    // Handles the GetCompilationResult Rd endpoint.
    // Triggers AssetDatabase.Refresh() in Unity Editor and checks compilation status.
    [SolutionComponent(Instantiation.DemandAnyThreadSafe)]
    public class UnityCompilationMcpHandler : IStartupActivity
    {
        private static readonly ILogger ourLogger = Logger.GetLogger<UnityCompilationMcpHandler>();

        private readonly BackendUnityHost _host;
        private readonly Action<Action> _rdQueue;

        public UnityCompilationMcpHandler(
            UnityCompilationMcpModelProvider modelProvider,
            IProtocol protocol,
            BackendUnityHost host)
        {
            _host = host;
            _rdQueue = protocol.Scheduler.Queue;

            RdTaskEx.SetAsync(modelProvider.Model.GetCompilationResult, async (lt, _) =>
            {
                ourLogger.Info("UnityCompilationMcpHandler: GetCompilationResult handler invoked");
                return await RefreshAndCheckCompilation(lt).ConfigureAwait(false);
            });
        }

        // Triggers AssetDatabase.Refresh(), waits for Unity reconnection (handles domain reload),
        // then calls GetCompilationResult to verify compilation succeeded.
        // _rdQueue dispatches actions to the Rd Shell Dispatcher thread so Rd RPCs are called correctly.
        private async Task<McpCompilationResponse> RefreshAndCheckCompilation(Lifetime lt)
        {
            var timeout = RdConnectionHelper.GetMcpToolTimeout();
            ourLogger.Info($"RefreshAndCheckCompilation: timeout={timeout.TotalSeconds}s");

            // Wait up to 30 seconds for Unity Editor to connect.
            // This covers the domain-reload window where the Rd connection is temporarily unavailable.
            // WHY NOT using MCP_TOOL_TIMEOUT for initial connection: 30s is intentionally fixed
            // across all tools so the initial wait behaviour is consistent regardless of env var.
            var unityModel = await RdConnectionHelper.WaitForUnityModel(
                _host, _rdQueue, lt, TimeSpan.FromSeconds(30)).ConfigureAwait(false);
            if (unityModel == null)
                return CompilationErrorResponse(
                    "Unity Editor did not connect within 30 seconds. Check idea.log and Editor.log to understand the situation. If Editor not running, use the `execute_run_configuration` tool to launch the `Start Unity` configuration, then retry.");

            // unityModel.Refresh.Start() is an Rd RPC and must be called on the Rd scheduler thread.
            // Schedule it via _rdQueue and capture the returned IRdTask.
            ourLogger.Info("RefreshAndCheckCompilation: starting Refresh");
            IRdTask<JetBrains.Core.Unit> rdRefreshTask;
            try
            {
                rdRefreshTask = await RdConnectionHelper.ScheduleOnRd(_rdQueue, () => unityModel.Refresh.Start(lt, RefreshType.Normal)).ConfigureAwait(false);
            }
            catch (Exception e)
            {
                ourLogger.Warn($"RefreshAndCheckCompilation: failed to start Refresh: {e.Message}");
                return CompilationErrorResponse($"Failed to start AssetDatabase.Refresh(): {e.Message}");
            }

            var refreshThrew = false;
            try
            {
                var refreshTask = RdConnectionHelper.AwaitRdTask(lt, rdRefreshTask);
                var timeoutTask = Task.Delay(timeout);
                if (await Task.WhenAny(refreshTask, timeoutTask).ConfigureAwait(false) != refreshTask)
                {
                    ourLogger.Warn($"RefreshAndCheckCompilation: Refresh timed out after {(int)timeout.TotalSeconds} seconds");
                    return CompilationErrorResponse($"AssetDatabase.Refresh() timed out after {(int)timeout.TotalSeconds} seconds.");
                }
                await refreshTask.ConfigureAwait(false);
                ourLogger.Info("RefreshAndCheckCompilation: Refresh completed");
            }
            catch (Exception e)
            {
                // Refresh may trigger a domain reload which disconnects Unity temporarily — not fatal
                ourLogger.Warn($"RefreshAndCheckCompilation: Refresh threw (likely domain reload): {e.Message}");
                refreshThrew = true;
            }

            // Wait for the model to be available again and call GetCompilationResult.
            // WHY two separate wait paths:
            // When refreshThrew, BackendUnityModel may still point to the stale pre-reload instance
            // because Rider has not yet propagated the disconnect. WaitForUnityModel would fire
            // immediately with that stale instance, causing GetCompilationResult to be cancelled.
            // WaitForModelReconnect requires a different instance, ensuring we get the post-reload model.
            // When !refreshThrew, no domain reload occurred and the existing model is still valid.
            ourLogger.Info("RefreshAndCheckCompilation: waiting for Unity model reconnection");

            if (!refreshThrew)
            {
                var reconnectedModel = await RdConnectionHelper.WaitForUnityModel(
                    _host, _rdQueue, lt, timeout).ConfigureAwait(false);
                if (reconnectedModel == null)
                    return CompilationErrorResponse(
                        $"Unity Editor did not reconnect within {(int)timeout.TotalSeconds} seconds after Refresh. However, before retrying or restarting Unity Editor, check idea.log and Editor.log to understand the situation.");
                ourLogger.Info("RefreshAndCheckCompilation: Unity model available, calling GetCompilationResult");
                return await CallGetCompilationResult(reconnectedModel, lt, timeout);
            }

            // WHY retry loop instead of a single WaitForModelReconnect call:
            // During domain reload, BackendUnityModel may briefly expose a transient instance
            // (a premature reconnect attempt whose Rd lifetime is already cancelled).
            // WaitForModelReconnect fires on this transient instance (different reference), but
            // GetCompilationResult.Start on it throws immediately — either OperationCanceledException
            // or a non-OCE Rd exception depending on how far the lifetime teardown has progressed.
            // TryCallGetCompilationResult returns null for both (see its Start catch block).
            // Updating previousModel and retrying waits for the next candidate, eventually reaching
            // the stable post-reload connection without surfacing a spurious error.
            var deadline = DateTime.Now.Add(timeout);
            var previousModel = unityModel;
            while (true)
            {
                var remaining = deadline - DateTime.Now;
                if (remaining <= TimeSpan.Zero)
                    return CompilationErrorResponse(
                        $"Unity Editor did not reconnect within {(int)timeout.TotalSeconds} seconds after Refresh. However, before retrying or restarting Unity Editor, check idea.log and Editor.log to understand the situation.");

                var candidate = await RdConnectionHelper.WaitForModelReconnect(
                    _host, _rdQueue, lt, previousModel, remaining).ConfigureAwait(false);
                if (candidate == null)
                    return CompilationErrorResponse(
                        $"Unity Editor did not reconnect within {(int)timeout.TotalSeconds} seconds after Refresh. However, before retrying or restarting Unity Editor, check idea.log and Editor.log to understand the situation.");

                ourLogger.Info("RefreshAndCheckCompilation: Unity model available, calling GetCompilationResult");
                var result = await TryCallGetCompilationResult(candidate, lt, timeout);
                if (result == null)
                {
                    ourLogger.Warn("RefreshAndCheckCompilation: GetCompilationResult cancelled on transient model, retrying");
                    previousModel = candidate;
                    continue;
                }

                return result;
            }
        }

        // Calls GetCompilationResult and returns the response. Throws on OperationCanceledException or other exceptions.
        private async Task<McpCompilationResponse> CallGetCompilationResult(BackendUnityModel model, Lifetime lt, TimeSpan timeout)
        {
            bool compilationSucceeded;
            try
            {
                var rdCompileTask = await RdConnectionHelper.ScheduleOnRd(_rdQueue, () => model.GetCompilationResult.Start(lt, JetBrains.Core.Unit.Instance)).ConfigureAwait(false);
                var compileTask = RdConnectionHelper.AwaitRdTask(lt, rdCompileTask);
                var timeoutTask = Task.Delay(timeout);
                if (await Task.WhenAny(compileTask, timeoutTask).ConfigureAwait(false) != compileTask)
                    return CompilationErrorResponse($"GetCompilationResult timed out after {(int)timeout.TotalSeconds} seconds.");
                compilationSucceeded = await compileTask.ConfigureAwait(false);
            }
            catch (OperationCanceledException)
            {
                return CompilationErrorResponse(
                    "Unity is currently compiling or reloading assemblies. " +
                    "Wait a few seconds and retry get_unity_compilation_result.");
            }
            catch (Exception e)
            {
                return CompilationErrorResponse($"GetCompilationResult failed: {e.Message}");
            }

            ourLogger.Info($"RefreshAndCheckCompilation: compilationSucceeded={compilationSucceeded}");
            if (!compilationSucceeded)
                return new McpCompilationResponse(
                    success: false,
                    errorMessage: "Unity compilation failed. Console logs during compilation are captured in the `logs` field of the response. If `logs` is empty, the compilation may have occurred before this tool triggered a refresh; use the `get_file_problems` tool, `getDiagnostics` tool, or read `editor.log` for error details."
                );
            return new McpCompilationResponse(success: true, errorMessage: "");
        }

        // Returns null if GetCompilationResult.Start threw (transient model — retry needed),
        // otherwise returns the compilation response.
        private async Task<McpCompilationResponse> TryCallGetCompilationResult(BackendUnityModel model, Lifetime lt, TimeSpan timeout)
        {
            // Separate Start from result-awaiting so transient-model exceptions from Start
            // are always treated as retry signals, regardless of exception type.
            // WHY: on a dying model, Start may throw OperationCanceledException OR a
            // non-OCE Rd exception (e.g. InvalidOperationException "Lifetime is not alive").
            // Both indicate the same transient-model condition and warrant a retry.
            IRdTask<bool> rdCompileTask;
            try
            {
                rdCompileTask = await RdConnectionHelper.ScheduleOnRd(
                    _rdQueue, () => model.GetCompilationResult.Start(lt, JetBrains.Core.Unit.Instance)
                ).ConfigureAwait(false);
            }
            catch (Exception ex)
            {
                ourLogger.Warn($"RefreshAndCheckCompilation: GetCompilationResult.Start threw {ex.GetType().Name} on transient model, retrying: {ex.Message}");
                return null;
            }

            try
            {
                var compileTask = RdConnectionHelper.AwaitRdTask(lt, rdCompileTask);
                var timeoutTask = Task.Delay(timeout);
                if (await Task.WhenAny(compileTask, timeoutTask).ConfigureAwait(false) != compileTask)
                    return CompilationErrorResponse($"GetCompilationResult timed out after {(int)timeout.TotalSeconds} seconds.");
                var compilationSucceeded = await compileTask.ConfigureAwait(false);
                ourLogger.Info($"RefreshAndCheckCompilation: compilationSucceeded={compilationSucceeded}");
                if (!compilationSucceeded)
                    return new McpCompilationResponse(
                        success: false,
                        errorMessage: "Unity compilation failed. Console logs during compilation are captured in the `logs` field of the response. If `logs` is empty, the compilation may have occurred before this tool triggered a refresh; use the `get_file_problems` tool, `getDiagnostics` tool, or read `editor.log` for error details."
                    );
                return new McpCompilationResponse(success: true, errorMessage: "");
            }
            catch (OperationCanceledException)
            {
                // Rd task was cancelled — transient model, signal retry
                return null;
            }
            catch (Exception e)
            {
                return CompilationErrorResponse($"GetCompilationResult failed: {e.Message}");
            }
        }

        private static McpCompilationResponse CompilationErrorResponse(string message) =>
            new McpCompilationResponse(success: false, errorMessage: message);
    }
}
