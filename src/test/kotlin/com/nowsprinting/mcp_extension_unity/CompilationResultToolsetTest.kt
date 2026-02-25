package com.nowsprinting.mcp_extension_unity

import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Test

class CompilationResultToolsetTest {

    @Test
    fun `CompilationErrorResult serializes to error pattern`() {
        val result: CompilationResult = CompilationErrorResult(errorMessage = "Unity Editor is not connected.")
        val json = Json.encodeToString(result)
        assertEquals("""{"success":false,"errorMessage":"Unity Editor is not connected."}""", json)
    }

    @Test
    fun `CompilationSuccessResult serializes correctly`() {
        val result: CompilationResult = CompilationSuccessResult
        val json = Json.encodeToString(result)
        assertEquals("""{"success":true}""", json)
    }

    @Test
    fun `CompilationErrorResult with compilation failure serializes correctly`() {
        val result: CompilationResult = CompilationErrorResult(
            errorMessage = "Unity compilation failed. Fix compiler errors before running tests."
        )
        val json = Json.encodeToString(result)
        assertEquals(
            """{"success":false,"errorMessage":"Unity compilation failed. Fix compiler errors before running tests."}""",
            json
        )
    }
}
