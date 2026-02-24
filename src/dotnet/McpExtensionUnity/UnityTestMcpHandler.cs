// All resharper-unity APIs used in this file (BackendUnityHost, BackendUnityModel,
// UnitTestLaunch, TestFilter, TestResult, RunResult, TestMode, RefreshType) are sourced from the
// Apache 2.0 open-source resharper-unity plugin:
//   https://github.com/JetBrains/resharper-unity
// Key reference files:
//   BackendUnityHost.cs      — BackendUnityHost, IsConnectionEstablished(), BackendUnityModel field
//   RunViaUnityEditorStrategy.cs — UnitTestLaunch construction and RunUnitTestLaunch invocation pattern
//   UnityNUnitServiceProvider.cs — TestFilter usage and test execution flow

using System;
using System.Collections.Concurrent;
using System.Collections.Generic;
using System.Linq;
using System.Threading;
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
    // Binds UnityTestMcpModel to the solution protocol and handles RunTests / GetCompilationResult calls.
    // Bridges: Kotlin Frontend → (custom Rd) → C# Backend → BackendUnityModel → Unity Editor.
    [SolutionComponent(Instantiation.DemandAnyThreadSafe)]
    public class UnityTestMcpHandler : IStartupActivity
    {
        private static readonly ILogger ourLogger = Logger.GetLogger<UnityTestMcpHandler>();

        public UnityTestMcpHandler(
            Lifetime lifetime,
            ISolution solution,
            IProtocol protocol,
            BackendUnityHost backendUnityHost)
        {
            ourLogger.Info("UnityTestMcpHandler: constructor called, binding Rd model");
            var model = new UnityTestMcpModel(lifetime, protocol);

            RdTaskEx.SetAsync(model.RunTests, async (lt, request) =>
            {
                ourLogger.Info("UnityTestMcpHandler: RunTests handler invoked");
                ourLogger.Info($"  TestMode={request.TestMode}, " +
                               $"Assemblies=[{string.Join(",", request.Filter.AssemblyNames)}], " +
                               $"Tests=[{string.Join(",", request.Filter.TestNames)}], " +
                               $"Groups=[{string.Join(",", request.Filter.GroupNames)}], " +
                               $"Categories=[{string.Join(",", request.Filter.CategoryNames)}]");

                // Check Unity Editor connectivity
                var isConnected = backendUnityHost.IsConnectionEstablished();
                ourLogger.Info($"  IsConnectionEstablished={isConnected}");
                if (!isConnected)
                    return ErrorResponse("Unity Editor is not connected to Rider. Please open Unity Editor with the project.");

                var backendUnityModel = backendUnityHost.BackendUnityModel.Value;
                ourLogger.Info($"  BackendUnityModel={backendUnityModel?.GetType().Name ?? "null"}");
                if (backendUnityModel == null)
                    return ErrorResponse("Unity Editor is not connected to Rider. Please open Unity Editor with the project.");

                // Refresh assets and check compilation before running tests
                ourLogger.Info("UnityTestMcpHandler: RunTests - starting compilation check");
                var compilationResult = await RefreshAndCheckCompilation(lt, backendUnityHost).ConfigureAwait(false);
                if (!compilationResult.Success)
                    return ErrorResponse(compilationResult.ErrorMessage);
                ourLogger.Info("UnityTestMcpHandler: RunTests - compilation check passed");

                // Re-acquire model after potential reconnection (Refresh may trigger domain reload)
                backendUnityModel = backendUnityHost.BackendUnityModel.Value;
                if (backendUnityModel == null)
                    return ErrorResponse("Unity Editor disconnected after compilation check.");

                // Build test filters from the MCP request
                var testFilters = BuildTestFilters(request.Filter);
                ourLogger.Info($"  TestFilters count={testFilters.Count}");
                var testMode = request.TestMode == McpTestMode.PlayMode ? TestMode.Play : TestMode.Edit;

                var sessionId = Guid.NewGuid();
                var launch = new UnitTestLaunch(
                    sessionId: sessionId,
                    testFilters: testFilters,
                    testMode: testMode,
                    clientControllerInfo: null
                );
                ourLogger.Info($"  UnitTestLaunch created, sessionId={sessionId}");

                // Read timeout from MCP_TOOL_TIMEOUT env var (seconds). Default: 300 (5 minutes).
                var timeoutSeconds = 300;
                var envTimeout = Environment.GetEnvironmentVariable("MCP_TOOL_TIMEOUT");
                if (envTimeout != null && int.TryParse(envTimeout, out var parsed) && parsed > 0)
                    timeoutSeconds = parsed;
                ourLogger.Info($"  Timeout={timeoutSeconds}s");

                // Collect results asynchronously.
                // ConcurrentDictionary provides thread-safe access across the Rd scheduler thread
                // (TestResult.Advise callbacks) and the thread pool thread (after await tcs.Task).
                // Keying by testId also deduplicates: if the same test reports multiple terminal
                // statuses, only the last one is retained (last-write-wins).
                var testResults = new ConcurrentDictionary<string, TestResult>();
                var tcs = new TaskCompletionSource<RunResult>(TaskCreationOptions.RunContinuationsAsynchronously);

                // Cancel TCS when the Rd lifetime ends (protocol disconnect or Kotlin coroutine cancel).
                // Runs on the Rd scheduler thread; TrySetCanceled is thread-safe.
                lt.OnTermination(() => tcs.TrySetCanceled());

                // Monitor for Unity Editor disconnection during test execution.
                // BackendUnityModel.Advise fires immediately with the current value, then again on change.
                // When Unity disconnects, the model becomes null — signal failure immediately.
                // Runs on the Rd scheduler thread; TrySetException is thread-safe.
                backendUnityHost.BackendUnityModel.Advise(lt, unityModel =>
                {
                    if (unityModel == null)
                        tcs.TrySetException(new Exception(
                            "Unity Editor disconnected during test execution. " +
                            "This may be caused by a domain reload, crash, or the editor being closed."));
                });

                // Subscribe BEFORE setting the launch to avoid missing early events
                launch.TestResult.Advise(lt, result =>
                {
                    // Only collect terminal statuses (not Pending/Running)
                    if (result.Status != Status.Pending && result.Status != Status.Running)
                    {
                        ourLogger.Info($"  TestResult received: testId={result.TestId}, parentId={result.ParentId ?? "null"}, status={result.Status}");
                        testResults[result.TestId] = result;
                    }
                });
                launch.RunResult.Advise(lt, runResult =>
                {
                    ourLogger.Info($"  RunResult received: passed={runResult.Passed}, testResults.Count={testResults.Count}");
                    tcs.TrySetResult(runResult);
                });

                // Trigger test execution in Unity Editor
                backendUnityModel.UnitTestLaunch.Value = launch;
                ourLogger.Info("  UnitTestLaunch.Value set");
                // Fire-and-forget: results come via TestResult/RunResult signals
                var _ = backendUnityModel.RunUnitTestLaunch.Start(lt, Unit.Instance);
                ourLogger.Info("  RunUnitTestLaunch.Start called");

                // Wait for completion with configurable timeout.
                // Timeout fires TrySetException (not TrySetCanceled) to distinguish from
                // lt.OnTermination, which fires TrySetCanceled.
                RunResult finalResult;
                using (var cts = new CancellationTokenSource(TimeSpan.FromSeconds(timeoutSeconds)))
                {
                    cts.Token.Register(
                        () => tcs.TrySetException(
                            new Exception($"Test execution timed out after {timeoutSeconds} seconds.")),
                        useSynchronizationContext: false);
                    try
                    {
                        finalResult = await tcs.Task.ConfigureAwait(false);
                    }
                    catch (OperationCanceledException)
                    {
                        // Rd lifetime ended: protocol disconnection or Kotlin coroutine cancellation
                        TryAbortLaunch(lt, backendUnityHost, launch);
                        return ErrorResponse("Test execution was cancelled due to protocol disconnection or Kotlin coroutine cancellation.");
                    }
                    catch (Exception ex)
                    {
                        // Timeout or Unity Editor disconnection
                        TryAbortLaunch(lt, backendUnityHost, launch);
                        return ErrorResponse(ex.Message);
                    }
                }

                // Take a snapshot to prevent concurrent modification during BuildResponse iteration.
                // Any late-arriving TestResult callbacks after this point are safely ignored.
                var snapshot = testResults.Values.ToList();
                ourLogger.Info($"  Building response, snapshot.Count={snapshot.Count}");
                return BuildResponse(snapshot);
            });

            RdTaskEx.SetAsync(model.GetCompilationResult, async (lt, _) =>
            {
                ourLogger.Info("UnityTestMcpHandler: GetCompilationResult handler invoked");
                return await RefreshAndCheckCompilation(lt, backendUnityHost).ConfigureAwait(false);
            });

            ourLogger.Info("UnityTestMcpHandler: Rd handlers registered");
        }

        // Triggers AssetDatabase.Refresh(), waits for Unity reconnection (handles domain reload),
        // then calls GetCompilationResult to verify compilation succeeded.
        private static async Task<McpCompilationResponse> RefreshAndCheckCompilation(
            Lifetime lt, BackendUnityHost host)
        {
            if (!host.IsConnectionEstablished())
                return CompilationErrorResponse(
                    "Unity Editor is not connected to Rider. Please open Unity Editor with the project.");

            var model = host.BackendUnityModel.Value;
            if (model == null)
                return CompilationErrorResponse(
                    "Unity Editor is not connected to Rider. Please open Unity Editor with the project.");

            ourLogger.Info("RefreshAndCheckCompilation: starting Refresh");
            try
            {
                var refreshTask = AwaitRdTask(lt, model.Refresh.Start(lt, RefreshType.Normal));
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

            // Wait for the model to be available again (fires immediately if no reload occurred)
            ourLogger.Info("RefreshAndCheckCompilation: waiting for Unity model reconnection");
            var reconnectedModel = await WaitForUnityModel(lt, host, TimeSpan.FromMinutes(2))
                .ConfigureAwait(false);
            if (reconnectedModel == null)
                return CompilationErrorResponse(
                    "Unity Editor did not reconnect within 2 minutes after Refresh.");
            ourLogger.Info("RefreshAndCheckCompilation: Unity model available, calling GetCompilationResult");

            bool compilationSucceeded;
            try
            {
                var compileTask = AwaitRdTask(lt, reconnectedModel.GetCompilationResult.Start(lt, Unit.Instance));
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
        private static async Task<BackendUnityModel> WaitForUnityModel(
            Lifetime lt, BackendUnityHost host, TimeSpan timeout)
        {
            var tcs = new TaskCompletionSource<BackendUnityModel>(
                TaskCreationOptions.RunContinuationsAsynchronously);
            // Advise fires immediately with the current value if non-null
            host.BackendUnityModel.Advise(lt, m =>
            {
                if (m != null) tcs.TrySetResult(m);
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
        private static Task<T> AwaitRdTask<T>(Lifetime lt, IRdTask<T> rdTask)
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

        // Attempts to abort the Unity test launch. Best-effort: Unity may already be disconnected.
        // Verifies lifetime, model availability, and session ID before calling Abort.
        // Exceptions are caught and logged only — never propagated to the caller.
        private static void TryAbortLaunch(Lifetime lt, BackendUnityHost host, UnitTestLaunch launch)
        {
            try
            {
                if (!lt.IsAlive) return;
                var model = host.BackendUnityModel.Value;
                if (model == null) return;
                var currentLaunch = model.UnitTestLaunch.Value;
                if (currentLaunch == null || currentLaunch.SessionId != launch.SessionId) return;
                ourLogger.Info($"TryAbortLaunch: aborting session {launch.SessionId}");
                launch.Abort.Start(lt, Unit.Instance);
            }
            catch (Exception e)
            {
                ourLogger.Warn($"TryAbortLaunch: failed (Unity may be disconnected): {e.Message}");
            }
        }

        private static McpRunTestsResponse ErrorResponse(string message) =>
            new McpRunTestsResponse(
                success: false,
                errorMessage: message,
                testResults: new List<McpTestResultItem>()
            );

        private static McpCompilationResponse CompilationErrorResponse(string message) =>
            new McpCompilationResponse(success: false, errorMessage: message);

        private static List<TestFilter> BuildTestFilters(McpTestFilter filter)
        {
            var testNames = new List<string>(filter.TestNames);
            var groupNames = new List<string>(filter.GroupNames);
            var categoryNames = new List<string>(filter.CategoryNames);

            // One TestFilter per assembly for proper Unity test filtering
            if (filter.AssemblyNames.Count > 0)
            {
                var filters = new List<TestFilter>();
                foreach (var assembly in filter.AssemblyNames)
                    filters.Add(new TestFilter(assembly, testNames, groupNames, categoryNames));
                return filters;
            }

            // assemblyNames is validated by the Kotlin frontend before the Rd call reaches here
            throw new InvalidOperationException("BuildTestFilters called with no assembly names. Kotlin frontend should have rejected the request.");
        }

        private static McpRunTestsResponse BuildResponse(List<TestResult> testResults)
        {
            var items = new List<McpTestResultItem>();
            foreach (var result in testResults)
            {
                items.Add(new McpTestResultItem(
                    testId: result.TestId,
                    parentId: result.ParentId ?? "",
                    output: result.Output,
                    duration: result.Duration,
                    status: ToMcpStatus(result.Status)
                ));
            }
            return new McpRunTestsResponse(
                success: true,
                errorMessage: "",
                testResults: items
            );
        }

        private static McpTestResultStatus ToMcpStatus(Status status)
        {
            switch (status)
            {
                case Status.Success: return McpTestResultStatus.Success;
                case Status.Failure: return McpTestResultStatus.Failure;
                case Status.Ignored: return McpTestResultStatus.Ignored;
                case Status.Inconclusive: return McpTestResultStatus.Inconclusive;
                // Throwing on unknown status would crash the handler and drop the entire response.
                // Mapping to Inconclusive lets the run complete and surfaces the issue via logs.
                default:
                    ourLogger.Warn($"Unexpected test status: {status}, mapping to Inconclusive");
                    return McpTestResultStatus.Inconclusive;
            }
        }
    }
}
