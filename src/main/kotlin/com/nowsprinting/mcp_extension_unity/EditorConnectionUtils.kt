package com.nowsprinting.mcp_extension_unity

import com.jetbrains.rd.util.lifetime.Lifetime
import com.jetbrains.rd.util.reactive.IOptProperty
import com.jetbrains.rd.util.reactive.valueOrDefault
import kotlinx.coroutines.suspendCancellableCoroutine
import java.util.Timer
import java.util.TimerTask
import kotlin.coroutines.resume

object EditorConnectionUtils {
    const val CONNECTION_WAIT_TIMEOUT_MS = 30_000L

    suspend fun awaitEditorConnection(
        connectedProperty: IOptProperty<Boolean>,
        timeoutMs: Long = CONNECTION_WAIT_TIMEOUT_MS
    ): Boolean {
        if (connectedProperty.valueOrDefault(false)) return true
        return suspendCancellableCoroutine { continuation ->
            val timer = Timer("EditorConnectionTimeout", /* isDaemon= */ true)
            timer.schedule(object : TimerTask() {
                override fun run() {
                    timer.cancel()
                    if (!continuation.isCompleted) continuation.resume(false)
                }
            }, timeoutMs)
            connectedProperty.advise(Lifetime.Eternal) { value ->
                if (value && !continuation.isCompleted) {
                    timer.cancel()
                    continuation.resume(true)
                }
            }
            continuation.invokeOnCancellation { timer.cancel() }
        }
    }
}
