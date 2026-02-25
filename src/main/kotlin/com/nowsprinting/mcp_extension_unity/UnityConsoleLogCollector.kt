package com.nowsprinting.mcp_extension_unity

import com.jetbrains.rd.util.lifetime.LifetimeDefinition
import com.jetbrains.rd.util.reactive.ISource
import com.jetbrains.rider.plugins.unity.model.LogEvent
import com.jetbrains.rider.plugins.unity.model.LogEventType
import java.util.concurrent.CopyOnWriteArrayList

data class CollectedLogEntry(
    val type: String,
    val message: String,
    val stackTrace: String
)

class UnityConsoleLogCollector(
    private val onConsoleLogEvent: ISource<LogEvent>
) {
    private val logs = CopyOnWriteArrayList<CollectedLogEntry>()
    private var lifetimeDefinition: LifetimeDefinition? = null

    companion object {
        internal fun toLogTypeName(type: LogEventType): String = when (type) {
            LogEventType.Error -> "Error"
            LogEventType.Warning -> "Warning"
            LogEventType.Message -> "Message"
        }

        internal fun toCollectedLogEntry(event: LogEvent): CollectedLogEntry = CollectedLogEntry(
            type = toLogTypeName(event.type),
            message = event.message,
            stackTrace = event.stackTrace
        )
    }

    fun start() {
        val ltd = LifetimeDefinition()
        lifetimeDefinition = ltd
        onConsoleLogEvent.advise(ltd) { event ->
            logs.add(toCollectedLogEntry(event))
        }
    }

    fun stop(): List<CollectedLogEntry> {
        lifetimeDefinition?.terminate()
        lifetimeDefinition = null
        return logs.toList()
    }
}
