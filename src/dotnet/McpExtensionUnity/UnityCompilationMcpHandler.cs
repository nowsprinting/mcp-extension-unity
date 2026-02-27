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
            UnityTestMcpModelProvider modelProvider,
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
            if (!_host.IsConnectionEstablished())
                return CompilationErrorResponse(
                    "Unity Editor is not connected to Rider. Please open Unity Editor with the project.");

            var unityModel = _host.BackendUnityModel.Value;
            if (unityModel == null)
                return CompilationErrorResponse(
                    "Unity Editor is not connected to Rider. Please open Unity Editor with the project.");

            // unityModel.Refresh.Start() is an Rd RPC and must be called on the Rd scheduler thread.
            // Schedule it via _rdQueue and capture the returned IRdTask.
            ourLogger.Info("RefreshAndCheckCompilation: starting Refresh");
            IRdTask<JetBrains.Core.Unit> rdRefreshTask;
            try
            {
                rdRefreshTask = await ScheduleOnRd(() => unityModel.Refresh.Start(lt, RefreshType.Normal)).ConfigureAwait(false);
            }
            catch (Exception e)
            {
                ourLogger.Warn($"RefreshAndCheckCompilation: failed to start Refresh: {e.Message}");
                return CompilationErrorResponse($"Failed to start AssetDatabase.Refresh(): {e.Message}");
            }

            try
            {
                var refreshTask = AwaitRdTask(lt, rdRefreshTask);
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
            }

            // Wait for the model to be available again (fires immediately if no reload occurred).
            // WaitForUnityModel schedules the Advise call on the Rd scheduler thread.
            ourLogger.Info("RefreshAndCheckCompilation: waiting for Unity model reconnection");
            var reconnectedModel = await WaitForUnityModel(lt, TimeSpan.FromMinutes(2)).ConfigureAwait(false);
            if (reconnectedModel == null)
                return CompilationErrorResponse(
                    "Unity Editor did not reconnect within 2 minutes after Refresh.");
            ourLogger.Info("RefreshAndCheckCompilation: Unity model available, calling GetCompilationResult");

            // GetCompilationResult.Start() is an Rd RPC and must be called on the Rd scheduler thread.
            bool compilationSucceeded;
            try
            {
                var rdCompileTask = await ScheduleOnRd(() => reconnectedModel.GetCompilationResult.Start(lt, JetBrains.Core.Unit.Instance)).ConfigureAwait(false);
                var compileTask = AwaitRdTask(lt, rdCompileTask);
                var timeoutTask = Task.Delay(TimeSpan.FromMinutes(1));
                if (await Task.WhenAny(compileTask, timeoutTask).ConfigureAwait(false) != compileTask)
                    return CompilationErrorResponse("GetCompilationResult timed out after 1 minute.");
                compilationSucceeded = await compileTask.ConfigureAwait(false);
            }
            catch (Exception e)
            {
                return CompilationErrorResponse($"GetCompilationResult failed: {e.Message}");
            }

            ourLogger.Info($"RefreshAndCheckCompilation: compilationSucceeded={compilationSucceeded}");
            if (!compilationSucceeded)
                return new McpCompilationResponse(
                    success: false,
                    errorMessage: "Unity compilation failed. Fix compiler errors before running tests."
                );
            return new McpCompilationResponse(success: true, errorMessage: "");
        }

        // Waits for BackendUnityModel to become non-null. Fires immediately if already connected.
        // The Advise call is scheduled on the Rd scheduler thread via _rdQueue to satisfy Rd threading requirements.
        private async Task<BackendUnityModel> WaitForUnityModel(Lifetime lt, TimeSpan timeout)
        {
            var tcs = new TaskCompletionSource<BackendUnityModel>(
                TaskCreationOptions.RunContinuationsAsynchronously);
            // Advise must be called on the Rd scheduler thread.
            // If the model is already non-null, Advise fires immediately on the scheduler thread,
            // setting the TCS result before we even reach Task.WhenAny.
            _rdQueue(() =>
            {
                _host.BackendUnityModel.Advise(lt, m =>
                {
                    if (m != null) tcs.TrySetResult(m);
                });
            });
            var reconnectTask = tcs.Task;
            var timeoutTask = Task.Delay(timeout);
            if (await Task.WhenAny(reconnectTask, timeoutTask).ConfigureAwait(false) != reconnectTask)
                return null;
            return await reconnectTask.ConfigureAwait(false);
        }

        // Converts IRdTask<T> to Task<T> using Advise on the result property.
        // Advise fires once when the task result is set (not with the initial null state).
        // RdTaskResult<T>.Unwrap() returns the value on success, throws on failure/cancellation.
        private Task<T> AwaitRdTask<T>(Lifetime lt, IRdTask<T> rdTask)
        {
            var tcs = new TaskCompletionSource<T>(TaskCreationOptions.RunContinuationsAsynchronously);
            rdTask.Result.Advise(lt, result =>
            {
                if (result == null) return;
                try
                {
                    tcs.TrySetResult(result.Unwrap());
                }
                catch (Exception e)
                {
                    tcs.TrySetException(e);
                }
            });
            return tcs.Task;
        }

        // Schedules func on the Rd scheduler thread and returns a Task<T> that completes with the result.
        // Required when calling Rd RPCs (e.g., Start) from a TP worker thread.
        // Not naming IScheduler directly avoids coupling to a specific JetBrains.* namespace version.
        private Task<T> ScheduleOnRd<T>(Func<T> func)
        {
            var tcs = new TaskCompletionSource<T>(TaskCreationOptions.RunContinuationsAsynchronously);
            _rdQueue(() =>
            {
                try { tcs.TrySetResult(func()); }
                catch (Exception e) { tcs.TrySetException(e); }
            });
            return tcs.Task;
        }

        private static McpCompilationResponse CompilationErrorResponse(string message) =>
            new McpCompilationResponse(success: false, errorMessage: message);
    }
}
