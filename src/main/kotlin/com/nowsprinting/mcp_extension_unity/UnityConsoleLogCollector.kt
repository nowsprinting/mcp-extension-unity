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
        internal fun toLogTypeName(type: LogEventType): String = TODO()

        internal fun toCollectedLogEntry(event: LogEvent): CollectedLogEntry = TODO()
    }

    fun start() {
        // TODO: implement
    }

    fun stop(): List<CollectedLogEntry> {
        // TODO: implement
        return emptyList()
    }
}
