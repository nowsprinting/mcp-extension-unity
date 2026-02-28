// All resharper-unity APIs used in this file (BackendUnityHost, BackendUnityModel,
// UnitTestLaunch, TestFilter, TestResult, RunResult, TestMode) are sourced from the
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
    // Handles the RunTests Rd endpoint.
    // Bridges: Kotlin Frontend → (custom Rd) → C# Backend → BackendUnityModel → Unity Editor.
    // GetCompilationResult is handled by UnityCompilationMcpHandler.
    [SolutionComponent(Instantiation.DemandAnyThreadSafe)]
    public class UnityTestMcpHandler : IStartupActivity
    {
        private static readonly ILogger ourLogger = Logger.GetLogger<UnityTestMcpHandler>();

        public UnityTestMcpHandler(
            UnityTestMcpModelProvider modelProvider,
            IProtocol protocol,
            BackendUnityHost backendUnityHost)
        {
            ourLogger.Info("UnityTestMcpHandler: constructor called, binding Rd handler");

            // Capture scheduler.Queue as a delegate to avoid naming the IScheduler type directly.
            // All Rd protocol operations must be dispatched via this delegate to ensure they run
            // on the Rd Shell Dispatcher thread.
            Action<Action> rdQueue = protocol.Scheduler.Queue;

            RdTaskEx.SetAsync(modelProvider.Model.RunTests, async (lt, request) =>
            {
                ourLogger.Info("UnityTestMcpHandler: RunTests handler invoked");
                ourLogger.Info($"  TestMode={request.TestMode}, " +
                               $"Assemblies=[{string.Join(",", request.Filter.AssemblyNames)}], " +
                               $"Tests=[{string.Join(",", request.Filter.TestNames)}], " +
                               $"Groups=[{string.Join(",", request.Filter.GroupNames)}], " +
                               $"Categories=[{string.Join(",", request.Filter.CategoryNames)}]");

                // Wait up to 30 seconds for Unity Editor to connect.
                // This covers the domain-reload window where the Rd connection is temporarily unavailable.
                var initialModel = await RdConnectionHelper.WaitForUnityModel(
                    backendUnityHost, rdQueue, lt, TimeSpan.FromSeconds(30)).ConfigureAwait(false);
                ourLogger.Info($"  BackendUnityModel={initialModel?.GetType().Name ?? "null"}");
                if (initialModel == null)
                    return ErrorResponse("Unity Editor did not connect within 30 seconds. Please open Unity Editor with the project.");

                // Build test params — no Rd operations, safe on any thread
                var testFilters = BuildTestFilters(request.Filter);
                ourLogger.Info($"  TestFilters count={testFilters.Count}");
                var testMode = request.TestMode == McpTestMode.PlayMode ? TestMode.Play : TestMode.Edit;

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

                // Guards against concurrent reconnection attempts from rapid null transitions.
                // 0 = idle, 1 = reconnecting.
                var reconnecting = 0;

                // All Rd operations (Advise, property set, RPC start) must run on the Rd scheduler thread.
                // Although we are still on the Rd scheduler thread here (no prior await),
                // we use ScheduleOnRd to keep the pattern consistent and future-proof.
                await RdConnectionHelper.ScheduleOnRd(rdQueue, () =>
                {
                    // Cancel TCS when the Rd lifetime ends (protocol disconnect or Kotlin coroutine cancel).
                    // Runs on the Rd scheduler thread; TrySetCanceled is thread-safe.
                    lt.OnTermination(() => tcs.TrySetCanceled());

                    // Monitor for Unity Editor disconnection during test execution.
                    // BackendUnityModel.Advise fires immediately with the current value, then again on change.
                    // On null (domain reload or crash): wait up to 2 minutes for reconnection.
                    // On reconnection: re-launch tests on the new model instance.
                    // Domain reload is expected when running PlayMode tests or when project settings
                    // require it, and causes a temporary null → non-null transition.
                    backendUnityHost.BackendUnityModel.Advise(lt, unityModel =>
                    {
                        if (unityModel == null)
                        {
                            if (Interlocked.CompareExchange(ref reconnecting, 1, 0) != 0)
                            {
                                ourLogger.Info("  BackendUnityModel became null (reconnection already in progress, ignoring)");
                                return;
                            }
                            ourLogger.Info("  BackendUnityModel became null (domain reload or disconnection), waiting for reconnection...");
                            // Advise callbacks run synchronously on the Rd thread; spawn reconnection off-thread.
                            Task.Run(async () =>
                            {
                                try
                                {
                                    var reconnected = await RdConnectionHelper.WaitForUnityModel(
                                        backendUnityHost, rdQueue, lt, TimeSpan.FromMinutes(2))
                                        .ConfigureAwait(false);
                                    if (reconnected == null)
                                    {
                                        tcs.TrySetException(new Exception(
                                            "Unity Editor did not reconnect within 2 minutes after domain reload. " +
                                            "This may be caused by a crash or the editor being closed."));
                                        return;
                                    }
                                    ourLogger.Info("  Unity Editor reconnected after domain reload, re-launching tests");
                                    await RdConnectionHelper.ScheduleOnRd(rdQueue, () =>
                                    {
                                        LaunchTests(reconnected, lt, testFilters, testMode, testResults, tcs);
                                    }).ConfigureAwait(false);
                                }
                                finally
                                {
                                    Interlocked.Exchange(ref reconnecting, 0);
                                }
                            });
                        }
                        else
                        {
                            ourLogger.Info($"  BackendUnityModel available: {unityModel.GetType().Name}");
                        }
                    });

                    // Initial test launch on the current model.
                    // Subscribe BEFORE setting the launch to avoid missing early events.
                    LaunchTests(initialModel, lt, testFilters, testMode, testResults, tcs);
                }).ConfigureAwait(false);

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
                        TryAbortLaunch(lt, backendUnityHost, rdQueue);
                        return ErrorResponse("Test execution was cancelled due to protocol disconnection or Kotlin coroutine cancellation.");
                    }
                    catch (Exception ex)
                    {
                        // Timeout or Unity Editor disconnection without reconnection
                        TryAbortLaunch(lt, backendUnityHost, rdQueue);
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

        // Attempts to abort the Unity test launch currently set on the model. Best-effort: Unity may
        // already be disconnected. Schedules the abort on the Rd scheduler thread via rdQueue.
        // Exceptions are caught and logged only — never propagated to the caller.
        private static void TryAbortLaunch(Lifetime lt, BackendUnityHost host, Action<Action> rdQueue)
        {
            rdQueue(() =>
            {
                try
                {
                    if (!lt.IsAlive) return;
                    var model = host.BackendUnityModel.Value;
                    if (model == null) return;
                    var launch = model.UnitTestLaunch.Value;
                    if (launch == null) return;
                    ourLogger.Info($"TryAbortLaunch: aborting session {launch.SessionId}");
                    launch.Abort.Start(lt, Unit.Instance);
                }
                catch (Exception e)
                {
                    ourLogger.Warn($"TryAbortLaunch: failed (Unity may be disconnected): {e.Message}");
                }
            });
        }

        // Creates a new UnitTestLaunch, subscribes its TestResult/RunResult signals to the shared
        // testResults/tcs, sets the launch on the model, and starts test execution.
        // Must be called on the Rd scheduler thread.
        // Called once initially and again after each domain-reload reconnection.
        private static void LaunchTests(
            BackendUnityModel model, Lifetime lt,
            List<TestFilter> testFilters, TestMode testMode,
            ConcurrentDictionary<string, TestResult> testResults,
            TaskCompletionSource<RunResult> tcs)
        {
            var launch = new UnitTestLaunch(
                sessionId: Guid.NewGuid(),
                testFilters: testFilters,
                testMode: testMode,
                clientControllerInfo: null);
            ourLogger.Info($"  UnitTestLaunch created, sessionId={launch.SessionId}");

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
            model.UnitTestLaunch.Value = launch;
            ourLogger.Info("  UnitTestLaunch.Value set");
            // Fire-and-forget: results come via TestResult/RunResult signals
            model.RunUnitTestLaunch.Start(lt, Unit.Instance);
            ourLogger.Info("  RunUnitTestLaunch.Start called");
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
