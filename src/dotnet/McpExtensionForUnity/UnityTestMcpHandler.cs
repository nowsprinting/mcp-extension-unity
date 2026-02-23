// All resharper-unity APIs used in this file (BackendUnityHost, BackendUnityModel,
// UnitTestLaunch, TestFilter, TestResult, RunResult, TestMode) are sourced from the
// Apache 2.0 open-source resharper-unity plugin:
//   https://github.com/JetBrains/resharper-unity
// Key reference files:
//   BackendUnityHost.cs      — BackendUnityHost, IsConnectionEstablished(), BackendUnityModel field
//   RunViaUnityEditorStrategy.cs — UnitTestLaunch construction and RunUnitTestLaunch invocation pattern
//   UnityNUnitServiceProvider.cs — TestFilter usage and test execution flow

using System;
using System.Collections.Generic;
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
using McpExtensionForUnity.Model;

namespace McpExtensionForUnity
{
    // Binds UnityTestMcpModel to the solution protocol and handles RunTests calls.
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

                if (!McpValidation.HasValidAssemblyNames(request.Filter.AssemblyNames))
                    return ErrorResponse("assemblyNames is required. Provide at least one test assembly name.");

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

                // Collect results asynchronously
                var testResults = new List<TestResult>();
                var tcs = new TaskCompletionSource<RunResult>(TaskCreationOptions.RunContinuationsAsynchronously);

                // Subscribe BEFORE setting the launch to avoid missing early events
                launch.TestResult.Advise(lt, result =>
                {
                    // Only collect terminal statuses (not Pending/Running)
                    if (result.Status != Status.Pending && result.Status != Status.Running)
                    {
                        ourLogger.Info($"  TestResult received: testId={result.TestId}, status={result.Status}");
                        testResults.Add(result);
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

                // Wait for completion with 5-minute timeout
                RunResult finalResult;
                using (var cts = new CancellationTokenSource(TimeSpan.FromMinutes(5)))
                {
                    cts.Token.Register(() => tcs.TrySetCanceled(), useSynchronizationContext: false);
                    try
                    {
                        finalResult = await tcs.Task.ConfigureAwait(false);
                    }
                    catch (OperationCanceledException)
                    {
                        return ErrorResponse("Test execution timed out after 5 minutes.");
                    }
                }

                ourLogger.Info($"  Building response, testResults.Count={testResults.Count}");
                return BuildResponse(testResults);
            });
            ourLogger.Info("UnityTestMcpHandler: Rd handler registered");
        }

        private static McpRunTestsResponse ErrorResponse(string message) =>
            new McpRunTestsResponse(
                success: false,
                errorMessage: message,
                passCount: 0,
                failCount: 0,
                skipCount: 0,
                inconclusiveCount: 0,
                failedTests: new List<McpTestDetail>(),
                inconclusiveTests: new List<McpTestDetail>()
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

            // assemblyNames should have been validated before calling this method
            throw new InvalidOperationException("BuildTestFilters called with no valid assembly names. Validate with HasValidAssemblyNames first.");
        }

        private static McpRunTestsResponse BuildResponse(List<TestResult> testResults)
        {
            var failedTests = new List<McpTestDetail>();
            var inconclusiveTests = new List<McpTestDetail>();
            int passCount = 0, failCount = 0, skipCount = 0, inconclusiveCount = 0;

            foreach (var result in testResults)
            {
                switch (result.Status)
                {
                    case Status.Success:
                        passCount++;
                        break;
                    case Status.Failure:
                        failCount++;
                        failedTests.Add(new McpTestDetail(result.TestId, result.Output, result.Duration));
                        break;
                    case Status.Ignored:
                        skipCount++;
                        break;
                    case Status.Inconclusive:
                        inconclusiveCount++;
                        inconclusiveTests.Add(new McpTestDetail(result.TestId, result.Output, result.Duration));
                        break;
                }
            }

            return new McpRunTestsResponse(
                success: true,
                errorMessage: "",
                passCount: passCount,
                failCount: failCount,
                skipCount: skipCount,
                inconclusiveCount: inconclusiveCount,
                failedTests: failedTests,
                inconclusiveTests: inconclusiveTests
            );
        }
    }
}
