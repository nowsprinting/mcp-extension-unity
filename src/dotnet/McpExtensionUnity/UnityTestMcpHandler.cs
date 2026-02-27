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
    // Binds UnityTestMcpModel to the solution protocol and handles the RunTests call.
    // Bridges: Kotlin Frontend → (custom Rd) → C# Backend → BackendUnityModel → Unity Editor.
    // GetCompilationResult is handled by UnityCompilationMcpHandler.
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

            // Capture scheduler.Queue as a delegate to avoid naming the IScheduler type directly.
            // All Rd protocol operations must be dispatched via this delegate to ensure they run
            // on the Rd Shell Dispatcher thread.
            Action<Action> rdQueue = protocol.Scheduler.Queue;

            new UnityCompilationMcpHandler(model, backendUnityHost, rdQueue);

            RdTaskEx.SetAsync(model.RunTests, async (lt, request) =>
            {
                ourLogger.Info("UnityTestMcpHandler: RunTests handler invoked");
                ourLogger.Info($"  TestMode={request.TestMode}, " +
                               $"Assemblies=[{string.Join(",", request.Filter.AssemblyNames)}], " +
                               $"Tests=[{string.Join(",", request.Filter.TestNames)}], " +
                               $"Groups=[{string.Join(",", request.Filter.GroupNames)}], " +
                               $"Categories=[{string.Join(",", request.Filter.CategoryNames)}]");

                // Check Unity Editor connectivity (on Rd scheduler thread — before any await)
                var isConnected = backendUnityHost.IsConnectionEstablished();
                ourLogger.Info($"  IsConnectionEstablished={isConnected}");
                if (!isConnected)
                    return ErrorResponse("Unity Editor is not connected to Rider. Please open Unity Editor with the project.");

                var initialModel = backendUnityHost.BackendUnityModel.Value;
                ourLogger.Info($"  BackendUnityModel={initialModel?.GetType().Name ?? "null"}");
                if (initialModel == null)
                    return ErrorResponse("Unity Editor is not connected to Rider. Please open Unity Editor with the project.");

                // Build test params — no Rd operations, safe on any thread
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

                // TCS is thread-safe; create it here so Advise callbacks inside ScheduleOnRd can reference it.
                // ConcurrentDictionary provides thread-safe access across the Rd scheduler thread
                // (TestResult.Advise callbacks) and the thread pool thread (after await tcs.Task).
                // Keying by testId also deduplicates: if the same test reports multiple terminal
                // statuses, only the last one is retained (last-write-wins).
                var testResults = new ConcurrentDictionary<string, TestResult>();
                var tcs = new TaskCompletionSource<RunResult>(TaskCreationOptions.RunContinuationsAsynchronously);
                McpRunTestsResponse setupError = null;

                // All Rd operations (Advise, property set, RPC start) must run on the Rd scheduler thread.
                // Although we are still on the Rd scheduler thread here (no prior await),
                // we use ScheduleOnRd to keep the pattern consistent and future-proof.
                await ScheduleOnRd(rdQueue, () =>
                {
                    // Re-acquire model after potential reconnection
                    var backendUnityModel = backendUnityHost.BackendUnityModel.Value;
                    if (backendUnityModel == null)
                    {
                        // Cannot return from a closure; signal the error through a captured variable
                        setupError = ErrorResponse("Unity Editor is not connected to Rider. Please open Unity Editor with the project.");
                        return;
                    }

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
                    backendUnityModel.RunUnitTestLaunch.Start(lt, Unit.Instance);
                    ourLogger.Info("  RunUnitTestLaunch.Start called");
                }).ConfigureAwait(false);

                if (setupError != null) return setupError;

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
                        TryAbortLaunch(lt, backendUnityHost, launch, rdQueue);
                        return ErrorResponse("Test execution was cancelled due to protocol disconnection or Kotlin coroutine cancellation.");
                    }
                    catch (Exception ex)
                    {
                        // Timeout or Unity Editor disconnection
                        TryAbortLaunch(lt, backendUnityHost, launch, rdQueue);
                        return ErrorResponse(ex.Message);
                    }
                }

                // Take a snapshot to prevent concurrent modification during BuildResponse iteration.
                // Any late-arriving TestResult callbacks after this point are safely ignored.
                var snapshot = testResults.Values.ToList();
                ourLogger.Info($"  Building response, snapshot.Count={snapshot.Count}");
                return BuildResponse(snapshot);
            });

            ourLogger.Info("UnityTestMcpHandler: Rd handlers registered");
        }

        // Attempts to abort the Unity test launch. Best-effort: Unity may already be disconnected.
        // Schedules the abort on the Rd scheduler thread via rdQueue to satisfy Rd threading requirements.
        // Exceptions are caught and logged only — never propagated to the caller.
        private static void TryAbortLaunch(Lifetime lt, BackendUnityHost host, UnitTestLaunch launch, Action<Action> rdQueue)
        {
            rdQueue(() =>
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
            });
        }

        // Schedules action on the Rd scheduler thread and returns a Task that completes when done.
        // Required when calling Rd Advise/Set/Start from a TP worker thread.
        // Not naming IScheduler directly avoids coupling to a specific JetBrains.* namespace version.
        private static Task ScheduleOnRd(Action<Action> rdQueue, Action action)
        {
            var tcs = new TaskCompletionSource<bool>(TaskCreationOptions.RunContinuationsAsynchronously);
            rdQueue(() =>
            {
                try { action(); tcs.TrySetResult(true); }
                catch (Exception e) { tcs.TrySetException(e); }
            });
            return tcs.Task;
        }

        private static McpRunTestsResponse ErrorResponse(string message) =>
            new McpRunTestsResponse(
                success: false,
                errorMessage: message,
                testResults: new List<McpTestResultItem>()
            );

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
