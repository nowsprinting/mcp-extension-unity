package com.nowsprinting.mcp_extension_unity

import com.jetbrains.rd.util.reactive.OptProperty
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class EditorConnectionUtilsTest {

    // Tests run with Dispatchers.Default to avoid blocking the EDT (IntelliJ platform test thread).
    // The implementation uses java.util.Timer for the timeout, which fires on a daemon thread
    // independently of the kotlinx.coroutines timer scheduler or the IntelliJ platform event loop.

    @Test
    fun `awaitEditorConnection - already connected - returns true`() = runBlocking(Dispatchers.Default) {
        val property = OptProperty<Boolean>(true)
        val result = EditorConnectionUtils.awaitEditorConnection(property)
        assertTrue(result)
    }

    @Test
    fun `awaitEditorConnection - not set then becomes true - returns true`() = runBlocking(Dispatchers.Default) {
        val property = OptProperty<Boolean>()
        launch {
            delay(100)
            property.set(true)
        }
        val result = EditorConnectionUtils.awaitEditorConnection(property, 5_000L)
        assertTrue(result)
    }

    @Test
    fun `awaitEditorConnection - false then becomes true - returns true`() = runBlocking(Dispatchers.Default) {
        val property = OptProperty<Boolean>(false)
        launch {
            delay(100)
            property.set(true)
        }
        val result = EditorConnectionUtils.awaitEditorConnection(property, 5_000L)
        assertTrue(result)
    }

    @Test
    fun `awaitEditorConnection - not connected timeout - returns false`() = runBlocking(Dispatchers.Default) {
        val property = OptProperty<Boolean>()
        val result = EditorConnectionUtils.awaitEditorConnection(property, 500L)
        assertFalse(result)
    }

    @Test
    fun `awaitEditorConnection - false timeout - returns false`() = runBlocking(Dispatchers.Default) {
        val property = OptProperty<Boolean>(false)
        val result = EditorConnectionUtils.awaitEditorConnection(property, 500L)
        assertFalse(result)
    }

    @Test
    fun `awaitEditorConnection - default timeout is 30 seconds`() {
        assertEquals(30_000L, EditorConnectionUtils.CONNECTION_WAIT_TIMEOUT_MS)
    }
}
