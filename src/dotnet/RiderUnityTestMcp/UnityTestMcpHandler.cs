using System;
using System.Collections.Generic;
using System.Threading;
using System.Threading.Tasks;
using JetBrains.Application.Parts;
using JetBrains.Core;
using JetBrains.Lifetimes;
using JetBrains.ProjectModel;
using JetBrains.Rd;
using JetBrains.Rd.Tasks;
using JetBrains.Rider.Model.Unity.BackendUnity;
using JetBrains.ReSharper.Plugins.Unity.Rider.Integration.Protocol;
using RiderUnityTestMcp.Model;

namespace RiderUnityTestMcp
{
    // Binds UnityTestMcpModel to the solution protocol and handles RunTests calls.
    // Bridges: Kotlin Frontend → (custom Rd) → C# Backend → BackendUnityModel → Unity Editor.
    [SolutionComponent(Instantiation.DemandAnyThreadSafe)]
    public class UnityTestMcpHandler
    {
        public UnityTestMcpHandler(
            Lifetime lifetime,
            ISolution solution,
            IProtocol protocol,
            BackendUnityHost backendUnityHost)
        {
            var model = new UnityTestMcpModel(lifetime, protocol);

            RdTaskEx.SetAsync(model.RunTests, async (lt, request) =>
            {
                // Check Unity Editor connectivity
                if (!backendUnityHost.IsConnectionEstablished())
                    return ErrorResponse("Unity Editor is not connected to Rider. Please open Unity Editor with the project.");

                var backendUnityModel = backendUnityHost.BackendUnityModel.Value;
                if (backendUnityModel == null)
                    return ErrorResponse("Unity Editor is not connected to Rider. Please open Unity Editor with the project.");

                // Build test filters from the MCP request
                var testFilters = BuildTestFilters(request.Filter);
                var testMode = request.TestMode == McpTestMode.PlayMode ? TestMode.Play : TestMode.Edit;

                var launch = new UnitTestLaunch(
                    sessionId: Guid.NewGuid(),
                    testFilters: testFilters,
                    testMode: testMode,
                    clientControllerInfo: null
                );

                // Collect results asynchronously
                var testResults = new List<TestResult>();
                var tcs = new TaskCompletionSource<RunResult>(TaskCreationOptions.RunContinuationsAsynchronously);

                // Subscribe BEFORE setting the launch to avoid missing early events
                launch.TestResult.Advise(lt, result =>
                {
                    // Only collect terminal statuses (not Pending/Running)
                    if (result.Status != Status.Pending && result.Status != Status.Running)
                        testResults.Add(result);
                });
                launch.RunResult.Advise(lt, runResult => tcs.TrySetResult(runResult));

                // Trigger test execution in Unity Editor
                backendUnityModel.UnitTestLaunch.Value = launch;
                // Fire-and-forget: results come via TestResult/RunResult signals
                var _ = backendUnityModel.RunUnitTestLaunch.Start(lt, Unit.Instance);

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

                return BuildResponse(testResults);
            });
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

            // No assembly filter: run all matching tests
            return new List<TestFilter>
            {
                new TestFilter("", testNames, groupNames, categoryNames)
            };
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
