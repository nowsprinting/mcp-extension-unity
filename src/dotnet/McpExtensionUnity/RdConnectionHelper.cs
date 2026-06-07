// Helper utilities shared by UnityTestMcpHandler and UnityCompilationMcpHandler.
// Extracted from the two handlers to avoid duplication.

using System;
using System.Threading.Tasks;
using JetBrains.Lifetimes;
using JetBrains.Rd.Tasks;
using JetBrains.Rider.Model.Unity.BackendUnity;
using JetBrains.ReSharper.Plugins.Unity.Rider.Integration.Protocol;
using JetBrains.Util;
using JetBrains.Util.Logging;

namespace McpExtensionUnity
{
    internal static class RdConnectionHelper
    {
        // WHY NOT Logger.GetLogger<RdConnectionHelper>(): static classes cannot be used as type
        // arguments in C# (language restriction); use the string overload instead.
        private static readonly ILogger ourLogger = Logger.GetLogger(nameof(RdConnectionHelper));

        // Waits for BackendUnityModel to become non-null. Returns immediately if already connected.
        // Advise call is scheduled on the Rd scheduler thread via rdQueue.
        internal static async Task<BackendUnityModel> WaitForUnityModel(
            BackendUnityHost host, Action<Action> rdQueue, Lifetime lt, TimeSpan timeout)
        {
            var tcs = new TaskCompletionSource<BackendUnityModel>(
                TaskCreationOptions.RunContinuationsAsynchronously);
            // Advise must be called on the Rd scheduler thread.
            // If the model is already non-null, Advise fires immediately on the scheduler thread,
            // setting the TCS result before we even reach Task.WhenAny.
            rdQueue(() =>
            {
                host.BackendUnityModel.Advise(lt, m =>
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

        // Schedules action on the Rd scheduler thread. Returns a Task that completes when done.
        // Required when calling Rd Advise/Set/Start from a TP worker thread.
        // Not naming IScheduler directly avoids coupling to a specific JetBrains.* namespace version.
        internal static Task ScheduleOnRd(Action<Action> rdQueue, Action action)
        {
            var tcs = new TaskCompletionSource<bool>(TaskCreationOptions.RunContinuationsAsynchronously);
            rdQueue(() =>
            {
                try { action(); tcs.TrySetResult(true); }
                catch (Exception e) { tcs.TrySetException(e); }
            });
            return tcs.Task;
        }

        // Schedules func on the Rd scheduler thread. Returns Task<T> with the result.
        // Required when calling Rd RPCs (e.g., Start) from a TP worker thread.
        internal static Task<T> ScheduleOnRd<T>(Action<Action> rdQueue, Func<T> func)
        {
            var tcs = new TaskCompletionSource<T>(TaskCreationOptions.RunContinuationsAsynchronously);
            rdQueue(() =>
            {
                try { tcs.TrySetResult(func()); }
                catch (Exception e) { tcs.TrySetException(e); }
            });
            return tcs.Task;
        }

        // Reads MCP_TOOL_TIMEOUT env var (milliseconds, per Claude Code spec). Defaults to 100000000ms (~28 hours) when absent or invalid.
        // Used by both test and compilation handlers so the same variable controls all phases.
        internal static TimeSpan GetMcpToolTimeout()
        {
            var env = Environment.GetEnvironmentVariable("MCP_TOOL_TIMEOUT");
            if (env != null && int.TryParse(env, out var ms) && ms > 0)
                return TimeSpan.FromMilliseconds(ms);
            return TimeSpan.FromMilliseconds(100_000_000);
        }

        // Waits for BackendUnityModel to be non-null AND IsConnectionEstablished()==true (stable).
        // Loops to reject transient models that appear briefly during domain reload before the stable
        // post-reload connection arrives. Uses 100ms polling for IsConnectionEstablished().
        // WHY NOT WaitForUnityModel alone: during domain reload, Unity briefly advertises a transient
        // BackendUnityModel (old port) that dies immediately; IsConnectionEstablished() returns false
        // for it. Using the transient model for LaunchTests causes RunUnitTestLaunch.Start to throw.
        internal static async Task<BackendUnityModel> WaitForStableUnityModel(
            BackendUnityHost host, Action<Action> rdQueue, Lifetime lt, TimeSpan timeout)
        {
            var deadline = DateTime.UtcNow.Add(timeout);
            while (true)
            {
                var remaining = deadline - DateTime.UtcNow;
                if (remaining <= TimeSpan.Zero) return null;

                if (await WaitForUnityModel(host, rdQueue, lt, remaining).ConfigureAwait(false) == null)
                    return null;

                // Poll until IsConnectionEstablished() is true or the model goes null (new reload cycle).
                while (DateTime.UtcNow < deadline)
                {
                    ourLogger.Info("WaitForStableUnityModel: poll: querying");
                    // Read both Rd-owned values atomically in one Rd-thread turn.
                    var (established, model) = await ScheduleOnRd(rdQueue,
                        () => (host.IsConnectionEstablished(), host.BackendUnityModel.Value)
                    ).ConfigureAwait(false);
                    ourLogger.Info($"WaitForStableUnityModel: poll: established={established}, model={model != null}");
                    if (established && model != null) return model;
                    if (model == null)
                    {
                        ourLogger.Info("WaitForStableUnityModel: poll: model null, restarting outer wait");
                        break;
                    }
                    await Task.Delay(100).ConfigureAwait(false);
                }
            }
        }

        // Waits for BackendUnityModel to become a non-null instance different from previousModel.
        // Use this after Refresh throws (domain reload detected) to wait for the post-reload reconnect.
        internal static async Task<BackendUnityModel> WaitForModelReconnect(
            BackendUnityHost host, Action<Action> rdQueue, Lifetime lt,
            BackendUnityModel previousModel, TimeSpan timeout)
        {
            var tcs = new TaskCompletionSource<BackendUnityModel>(
                TaskCreationOptions.RunContinuationsAsynchronously);
            // WHY !ReferenceEquals guard instead of re-using WaitForUnityModel:
            // After Refresh.Start() throws (domain reload in progress), BackendUnityModel may still
            // hold the stale pre-reload instance because Rider has not yet propagated the disconnect.
            // Advise on the same property would fire immediately with that stale instance, making
            // GetCompilationResult.Start() fail on a model that is being torn down.
            // Requiring a different instance ensures we wait for the actual post-reload reconnect.
            rdQueue(() =>
            {
                host.BackendUnityModel.Advise(lt, m =>
                {
                    if (m != null && !ReferenceEquals(m, previousModel)) tcs.TrySetResult(m);
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
        internal static Task<T> AwaitRdTask<T>(Lifetime lt, IRdTask<T> rdTask)
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
    }
}
