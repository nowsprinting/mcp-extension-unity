package com.nowsprinting.mcp_extension_unity

import com.nowsprinting.mcp_extension_unity.PlayControlTool.Companion.PlayAction
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PlayControlToolTest {

    @Test
    fun `parseAction - play returns PLAY`() {
        assertEquals(PlayAction.PLAY, PlayControlTool.parseAction("play"))
    }

    @Test
    fun `parseAction - Play returns PLAY (case insensitive)`() {
        assertEquals(PlayAction.PLAY, PlayControlTool.parseAction("Play"))
    }

    @Test
    fun `parseAction - stop returns STOP`() {
        assertEquals(PlayAction.STOP, PlayControlTool.parseAction("stop"))
    }

    @Test
    fun `parseAction - pause returns PAUSE`() {
        assertEquals(PlayAction.PAUSE, PlayControlTool.parseAction("pause"))
    }

    @Test
    fun `parseAction - resume returns RESUME`() {
        assertEquals(PlayAction.RESUME, PlayControlTool.parseAction("resume"))
    }

    @Test
    fun `parseAction - step returns STEP`() {
        assertEquals(PlayAction.STEP, PlayControlTool.parseAction("step"))
    }

    @Test
    fun `parseAction - status returns STATUS`() {
        assertEquals(PlayAction.STATUS, PlayControlTool.parseAction("status"))
    }

    @Test
    fun `parseAction - invalid returns null`() {
        assertNull(PlayControlTool.parseAction("invalid"))
    }

    @Test
    fun `parseAction - empty string returns null`() {
        assertNull(PlayControlTool.parseAction(""))
    }

    @Test
    fun `parseAction - blank string returns null`() {
        assertNull(PlayControlTool.parseAction("   "))
    }

    @Test
    fun `parseAction - null returns null`() {
        assertNull(PlayControlTool.parseAction(null))
    }

    @Test
    fun `PlayControlErrorResult serializes to error pattern`() {
        val result: PlayControlResult = PlayControlErrorResult(errorMessage = "Unity Editor is not connected to Rider.")
        val json = Json.encodeToString(result)
        assertEquals("""{"success":false,"errorMessage":"Unity Editor is not connected to Rider."}""", json)
    }

    @Test
    fun `PlayControlSuccessResult - play action serializes correctly`() {
        val result: PlayControlResult = PlayControlSuccessResult(action = "play", isPlaying = true, isPaused = false)
        val json = Json.encodeToString(result)
        assertEquals("""{"success":true,"action":"play","isPlaying":true,"isPaused":false}""", json)
    }

    @Test
    fun `PlayControlSuccessResult - status action serializes correctly`() {
        val result: PlayControlResult = PlayControlSuccessResult(action = "status", isPlaying = false, isPaused = false)
        val json = Json.encodeToString(result)
        assertEquals("""{"success":true,"action":"status","isPlaying":false,"isPaused":false}""", json)
    }
}
