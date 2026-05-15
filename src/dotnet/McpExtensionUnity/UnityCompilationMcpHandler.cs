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
            // Wait up to 30 seconds for Unity Editor to connect.
            // This covers the domain-reload window where the Rd connection is temporarily unavailable.
            var unityModel = await RdConnectionHelper.WaitForUnityModel(
                _host, _rdQueue, lt, TimeSpan.FromSeconds(30)).ConfigureAwait(false);
            if (unityModel == null)
                return CompilationErrorResponse(
                    "Unity Editor did not connect within 30 seconds. Please open Unity Editor with the project.");

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
                var timeoutTask = Task.Delay(TimeSpan.FromMinutes(2));
                if (await Task.WhenAny(refreshTask, timeoutTask).ConfigureAwait(false) != refreshTask)
                {
                    ourLogger.Warn("RefreshAndCheckCompilation: Refresh timed out after 2 minutes");
                    return CompilationErrorResponse("AssetDatabase.Refresh() timed out after 2 minutes.");
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
                    _host, _rdQueue, lt, TimeSpan.FromMinutes(2)).ConfigureAwait(false);
                if (reconnectedModel == null)
                    return CompilationErrorResponse(
                        "Unity Editor did not reconnect within 2 minutes after Refresh.");
                ourLogger.Info("RefreshAndCheckCompilation: Unity model available, calling GetCompilationResult");
                return await CallGetCompilationResult(reconnectedModel, lt);
            }

            // WHY retry loop instead of a single WaitForModelReconnect call:
            // During domain reload, BackendUnityModel may briefly expose a transient instance
            // (a premature reconnect attempt that gets immediately rejected — "lifetime is already canceled").
            // WaitForModelReconnect correctly fires on this transient instance (it is a different reference),
            // but GetCompilationResult on it throws OperationCanceledException right away.
            // Updating previousModel to the rejected instance and retrying waits for the next candidate,
            // eventually reaching the stable post-reload connection without surfacing a spurious error.
            var deadline = DateTime.Now.AddMinutes(2);
            var previousModel = unityModel;
            while (true)
            {
                var remaining = deadline - DateTime.Now;
                if (remaining <= TimeSpan.Zero)
                    return CompilationErrorResponse(
                        "Unity Editor did not reconnect within 2 minutes after Refresh.");

                var candidate = await RdConnectionHelper.WaitForModelReconnect(
                    _host, _rdQueue, lt, previousModel, remaining).ConfigureAwait(false);
                if (candidate == null)
                    return CompilationErrorResponse(
                        "Unity Editor did not reconnect within 2 minutes after Refresh.");

                ourLogger.Info("RefreshAndCheckCompilation: Unity model available, calling GetCompilationResult");
                var result = await TryCallGetCompilationResult(candidate, lt);
                if (result != null)
                    return result;

                ourLogger.Warn("RefreshAndCheckCompilation: GetCompilationResult cancelled on transient model, retrying");
                previousModel = candidate;
            }
        }

        // Calls GetCompilationResult and returns the response. Throws on OperationCanceledException or other exceptions.
        private async Task<McpCompilationResponse> CallGetCompilationResult(BackendUnityModel model, Lifetime lt)
        {
            bool compilationSucceeded;
            try
            {
                var rdCompileTask = await RdConnectionHelper.ScheduleOnRd(_rdQueue, () => model.GetCompilationResult.Start(lt, JetBrains.Core.Unit.Instance)).ConfigureAwait(false);
                var compileTask = RdConnectionHelper.AwaitRdTask(lt, rdCompileTask);
                var timeoutTask = Task.Delay(TimeSpan.FromMinutes(1));
                if (await Task.WhenAny(compileTask, timeoutTask).ConfigureAwait(false) != compileTask)
                    return CompilationErrorResponse("GetCompilationResult timed out after 1 minute.");
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

        // Returns null if GetCompilationResult was cancelled (transient model), otherwise returns the response.
        private async Task<McpCompilationResponse> TryCallGetCompilationResult(BackendUnityModel model, Lifetime lt)
        {
            try
            {
                var rdCompileTask = await RdConnectionHelper.ScheduleOnRd(_rdQueue, () => model.GetCompilationResult.Start(lt, JetBrains.Core.Unit.Instance)).ConfigureAwait(false);
                var compileTask = RdConnectionHelper.AwaitRdTask(lt, rdCompileTask);
                var timeoutTask = Task.Delay(TimeSpan.FromMinutes(1));
                if (await Task.WhenAny(compileTask, timeoutTask).ConfigureAwait(false) != compileTask)
                    return CompilationErrorResponse("GetCompilationResult timed out after 1 minute.");
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
                // Signal to the caller that this model was transient (retry needed)
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
