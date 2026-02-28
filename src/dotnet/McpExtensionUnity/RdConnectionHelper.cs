// Helper utilities shared by UnityTestMcpHandler and UnityCompilationMcpHandler.
// Extracted from the two handlers to avoid duplication.

using System;
using System.Threading.Tasks;
using JetBrains.Lifetimes;
using JetBrains.Rd.Tasks;
using JetBrains.Rider.Model.Unity.BackendUnity;
using JetBrains.ReSharper.Plugins.Unity.Rider.Integration.Protocol;

namespace McpExtensionUnity
{
    internal static class RdConnectionHelper
    {
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
