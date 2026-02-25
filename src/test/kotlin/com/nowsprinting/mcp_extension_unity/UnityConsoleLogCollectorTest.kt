package com.nowsprinting.mcp_extension_unity

import com.jetbrains.rider.plugins.unity.model.LogEvent
import com.jetbrains.rider.plugins.unity.model.LogEventMode
import com.jetbrains.rider.plugins.unity.model.LogEventType
import org.junit.Assert.assertEquals
import org.junit.Test

class UnityConsoleLogCollectorTest {

    @Test
    fun `toLogTypeName Error returns Error string`() {
        assertEquals("Error", UnityConsoleLogCollector.toLogTypeName(LogEventType.Error))
    }

    @Test
    fun `toLogTypeName Warning returns Warning string`() {
        assertEquals("Warning", UnityConsoleLogCollector.toLogTypeName(LogEventType.Warning))
    }

    @Test
    fun `toLogTypeName Message returns Message string`() {
        assertEquals("Message", UnityConsoleLogCollector.toLogTypeName(LogEventType.Message))
    }

    @Test
    fun `toCollectedLogEntry all fields mapped correctly`() {
        val event = LogEvent(
            time = 1000L,
            type = LogEventType.Error,
            mode = LogEventMode.Edit,
            message = "Something went wrong",
            stackTrace = "at Foo.Bar()"
        )
        val entry = UnityConsoleLogCollector.toCollectedLogEntry(event)
        assertEquals("Error", entry.type)
        assertEquals("Something went wrong", entry.message)
        assertEquals("at Foo.Bar()", entry.stackTrace)
    }

    @Test
    fun `toCollectedLogEntry empty stackTrace preserved as empty`() {
        val event = LogEvent(
            time = 0L,
            type = LogEventType.Message,
            mode = LogEventMode.Play,
            message = "Hello",
            stackTrace = ""
        )
        val entry = UnityConsoleLogCollector.toCollectedLogEntry(event)
        assertEquals("", entry.stackTrace)
    }
}
