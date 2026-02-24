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
        assertEquals("""{"success":false,"errorMessage":"Assembly not found."}""", json)
    }

    @Test
    fun `RunMethodInUnitySuccessResult serializes correctly`() {
        val result: RunMethodInUnityResult = RunMethodInUnitySuccessResult
        val json = Json.encodeToString(result)
        assertEquals("""{"success":true}""", json)
    }
}
