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

    // WHY java.util.Timer instead of withTimeout:
    //
    // In the IntelliJ platform test JVM, test methods run on the EDT (Event Dispatch Thread).
    // kotlinx.coroutines' withTimeout relies on DefaultDelay, whose cancellation callback is
    // dispatched back to the coroutine's event loop. When runBlocking is called from the EDT,
    // it creates an event loop ON the EDT — but the EDT is blocked by runBlocking itself,
    // so the cancellation callback can never be processed. The result: withTimeout never fires,
    // and the test hangs until Gradle's test-process timeout kills it (~20 minutes).
    //
    // Using Dispatchers.Default moves coroutine execution off the EDT but runBlocking still
    // blocks the EDT thread, reproducing the same deadlock via a different path.
    //
    // java.util.Timer runs on its own daemon thread and calls continuation.resume() directly,
    // bypassing the coroutine scheduler entirely. This fires reliably regardless of EDT state.
    //
    // Tests run with Dispatchers.Default so that delay() inside launch{} is dispatched to the
    // thread pool and does not also depend on the blocked EDT's event loop.

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
