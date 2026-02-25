package com.nowsprinting.mcp_extension_unity

import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class RunMethodInUnityToolsetTest {

    @Test
    fun `validateParam - valid string returns trimmed`() {
        assertEquals("myAssembly", RunMethodInUnityToolset.validateParam("assemblyName", "myAssembly"))
    }

    @Test
    fun `validateParam - string with whitespace returns trimmed`() {
        assertEquals("myAssembly", RunMethodInUnityToolset.validateParam("assemblyName", "  myAssembly  "))
    }

    @Test
    fun `validateParam - null returns null`() {
        assertNull(RunMethodInUnityToolset.validateParam("assemblyName", null))
    }

    @Test
    fun `validateParam - empty string returns null`() {
        assertNull(RunMethodInUnityToolset.validateParam("assemblyName", ""))
    }

    @Test
    fun `validateParam - blank string returns null`() {
        assertNull(RunMethodInUnityToolset.validateParam("assemblyName", "   "))
    }

    @Test
    fun `formatErrorMessage - message only when stackTrace is blank`() {
        val result = RunMethodInUnityToolset.formatErrorMessage("Type not found", "")
        assertEquals("Type not found", result)
    }

    @Test
    fun `formatErrorMessage - message with stackTrace combined`() {
        val result = RunMethodInUnityToolset.formatErrorMessage("Type not found", "at Foo.Bar()")
        assertEquals("Type not found\nStack trace:\nat Foo.Bar()", result)
    }

    @Test
    fun `RunMethodInUnityErrorResult serializes to error pattern`() {
        val result: RunMethodInUnityResult = RunMethodInUnityErrorResult(errorMessage = "Assembly not found.")
        val json = Json.encodeToString(result)
        assertEquals("""{"success":false,"errorMessage":"Assembly not found.","logs":[]}""", json)
    }

    @Test
    fun `RunMethodInUnitySuccessResult with empty logs serializes correctly`() {
        val result: RunMethodInUnityResult = RunMethodInUnitySuccessResult(emptyList())
        val json = Json.encodeToString(result)
        assertEquals("""{"success":true,"logs":[]}""", json)
    }

    @Test
    fun `RunMethodInUnitySuccessResult with logs serializes correctly`() {
        val logs = listOf(CollectedLogEntry(type = "Message", message = "Hello", stackTrace = ""))
        val result: RunMethodInUnityResult = RunMethodInUnitySuccessResult(logs)
        val json = Json.encodeToString(result)
        assertEquals("""{"success":true,"logs":[{"type":"Message","message":"Hello","stackTrace":""}]}""", json)
    }

    @Test
    fun `RunMethodInUnitySuccessResult log entry serializes all fields`() {
        val logs = listOf(
            CollectedLogEntry(type = "Error", message = "Crash!", stackTrace = "at Foo.Bar()")
        )
        val result: RunMethodInUnityResult = RunMethodInUnitySuccessResult(logs)
        val json = Json.encodeToString(result)
        assertEquals("""{"success":true,"logs":[{"type":"Error","message":"Crash!","stackTrace":"at Foo.Bar()"}]}""", json)
    }

    @Test
    fun `RunMethodInUnityErrorResult with logs serializes correctly`() {
        val logs = listOf(CollectedLogEntry(type = "Error", message = "Oops", stackTrace = ""))
        val result: RunMethodInUnityResult = RunMethodInUnityErrorResult(errorMessage = "err", logs = logs)
        val json = Json.encodeToString(result)
        assertEquals("""{"success":false,"errorMessage":"err","logs":[{"type":"Error","message":"Oops","stackTrace":""}]}""", json)
    }

    @Test
    fun `RunMethodInUnityErrorResult with default empty logs serializes correctly`() {
        val result: RunMethodInUnityResult = RunMethodInUnityErrorResult(errorMessage = "err")
        val json = Json.encodeToString(result)
        assertEquals("""{"success":false,"errorMessage":"err","logs":[]}""", json)
    }
}
