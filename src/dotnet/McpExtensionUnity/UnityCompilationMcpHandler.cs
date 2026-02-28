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
            }

            // Wait for the model to be available again (fires immediately if no reload occurred).
            // WaitForUnityModel schedules the Advise call on the Rd scheduler thread.
            ourLogger.Info("RefreshAndCheckCompilation: waiting for Unity model reconnection");
            var reconnectedModel = await RdConnectionHelper.WaitForUnityModel(_host, _rdQueue, lt, TimeSpan.FromMinutes(2)).ConfigureAwait(false);
            if (reconnectedModel == null)
                return CompilationErrorResponse(
                    "Unity Editor did not reconnect within 2 minutes after Refresh.");
            ourLogger.Info("RefreshAndCheckCompilation: Unity model available, calling GetCompilationResult");

            // GetCompilationResult.Start() is an Rd RPC and must be called on the Rd scheduler thread.
            bool compilationSucceeded;
            try
            {
                var rdCompileTask = await RdConnectionHelper.ScheduleOnRd(_rdQueue, () => reconnectedModel.GetCompilationResult.Start(lt, JetBrains.Core.Unit.Instance)).ConfigureAwait(false);
                var compileTask = RdConnectionHelper.AwaitRdTask(lt, rdCompileTask);
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

        private static McpCompilationResponse CompilationErrorResponse(string message) =>
            new McpCompilationResponse(success: false, errorMessage: message);
    }
}
