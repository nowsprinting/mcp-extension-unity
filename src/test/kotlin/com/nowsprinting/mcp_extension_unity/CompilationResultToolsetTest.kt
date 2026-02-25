package com.nowsprinting.mcp_extension_unity

import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Test

class CompilationResultToolsetTest {

    @Test
    fun `CompilationErrorResult_serializes_to_error_pattern`() {
        val result: CompilationResult = CompilationErrorResult(errorMessage = "Unity Editor is not connected.")
        val json = Json.encodeToString(result)
        assertEquals("""{"success":false,"errorMessage":"Unity Editor is not connected.","logs":[]}""", json)
    }

    @Test
    fun `CompilationSuccessResult_serializes_correctly`() {
        val result: CompilationResult = CompilationSuccessResult(emptyList())
        val json = Json.encodeToString(result)
        assertEquals("""{"success":true,"logs":[]}""", json)
    }

    @Test
    fun `CompilationErrorResult_with_compilation_failure_serializes_correctly`() {
        val result: CompilationResult = CompilationErrorResult(
            errorMessage = "Unity compilation failed. Fix compiler errors before running tests."
        )
        val json = Json.encodeToString(result)
        assertEquals(
            """{"success":false,"errorMessage":"Unity compilation failed. Fix compiler errors before running tests.","logs":[]}""",
            json
        )
    }

    @Test
    fun `CompilationSuccessResult_with_logs_serializes_correctly`() {
        val logs = listOf(CollectedLogEntry(type = "Message", message = "Reimporting...", stackTrace = ""))
        val result: CompilationResult = CompilationSuccessResult(logs)
        val json = Json.encodeToString(result)
        assertEquals(
            """{"success":true,"logs":[{"type":"Message","message":"Reimporting...","stackTrace":""}]}""",
            json
        )
    }

    @Test
    fun `CompilationErrorResult_with_logs_serializes_correctly`() {
        val logs = listOf(CollectedLogEntry(type = "Error", message = "CS0246: Type not found", stackTrace = ""))
        val result: CompilationResult = CompilationErrorResult(
            errorMessage = "Unity compilation failed. Fix compiler errors before running tests.",
            logs = logs
        )
        val json = Json.encodeToString(result)
        assertEquals(
            """{"success":false,"errorMessage":"Unity compilation failed. Fix compiler errors before running tests.","logs":[{"type":"Error","message":"CS0246: Type not found","stackTrace":""}]}""",
            json
        )
    }

    @Test
    fun `CompilationSuccessResult_log_entry_serializes_all_fields`() {
        val logs = listOf(
            CollectedLogEntry(type = "Warning", message = "Obsolete API", stackTrace = "at Foo.Bar()")
        )
        val result: CompilationResult = CompilationSuccessResult(logs)
        val json = Json.encodeToString(result)
        assertEquals(
            """{"success":true,"logs":[{"type":"Warning","message":"Obsolete API","stackTrace":"at Foo.Bar()"}]}""",
            json
        )
    }
}
