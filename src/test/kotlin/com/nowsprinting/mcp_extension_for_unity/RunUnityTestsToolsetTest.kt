package com.nowsprinting.mcp_extension_for_unity

import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RunUnityTestsToolsetTest {

    @Test
    fun `sanitizeAssemblyNames - null returns empty list`() {
        val result = RunUnityTestsToolset.sanitizeAssemblyNames(null)
        assertTrue(result.isEmpty())
    }

    @Test
    fun `sanitizeAssemblyNames - empty list returns empty list`() {
        val result = RunUnityTestsToolset.sanitizeAssemblyNames(emptyList())
        assertTrue(result.isEmpty())
    }

    @Test
    fun `sanitizeAssemblyNames - list with only blank strings returns empty`() {
        val result = RunUnityTestsToolset.sanitizeAssemblyNames(listOf("", "   ", "\t"))
        assertTrue(result.isEmpty())
    }

    @Test
    fun `sanitizeAssemblyNames - valid names are preserved`() {
        val result = RunUnityTestsToolset.sanitizeAssemblyNames(listOf("MyTests.EditMode", "MyTests.PlayMode"))
        assertEquals(listOf("MyTests.EditMode", "MyTests.PlayMode"), result)
    }

    @Test
    fun `sanitizeAssemblyNames - blank strings are filtered out`() {
        val result = RunUnityTestsToolset.sanitizeAssemblyNames(listOf("", "  "))
        assertTrue(result.isEmpty())
    }

    @Test
    fun `sanitizeAssemblyNames - mixed valid and blank strings keeps only valid`() {
        val result = RunUnityTestsToolset.sanitizeAssemblyNames(listOf("", "MyTests.EditMode", "  ", "MyTests.PlayMode"))
        assertEquals(listOf("MyTests.EditMode", "MyTests.PlayMode"), result)
    }

    @Test
    fun `TestErrorResult serializes to error pattern`() {
        val result: RunUnityTestsResult = TestErrorResult(message = "Some infrastructure error")
        val json = Json.encodeToString(result)
        assertEquals("""{"success":false,"message":"Some infrastructure error"}""", json)
    }

    @Test
    fun `TestRunResult with failures serializes to failure pattern`() {
        val result: RunUnityTestsResult = TestRunResult(
            passCount = 2,
            skipCount = 0,
            failCount = 1,
            inconclusiveCount = 0,
            failedTests = listOf(TestDetail(testId = "MyTest", output = "Expected true but was false", duration = 100)),
            inconclusiveTests = emptyList()
        )
        val json = Json.encodeToString(result)
        assertEquals(
            """{"success":false,"passCount":2,"skipCount":0,"failCount":1,"inconclusiveCount":0,"failedTests":[{"testId":"MyTest","output":"Expected true but was false","duration":100}],"inconclusiveTests":[]}""",
            json
        )
    }

    @Test
    fun `TestRunResult with inconclusives serializes to failure pattern`() {
        val result: RunUnityTestsResult = TestRunResult(
            passCount = 2,
            skipCount = 0,
            failCount = 0,
            inconclusiveCount = 1,
            failedTests = emptyList(),
            inconclusiveTests = listOf(TestDetail(testId = "MyTest", output = "Expected true but was false", duration = 100))
        )
        val json = Json.encodeToString(result)
        assertEquals(
            """{"success":false,"passCount":2,"skipCount":0,"failCount":0,"inconclusiveCount":1,"failedTests":[],"inconclusiveTests":[{"testId":"MyTest","output":"Expected true but was false","duration":100}]}""",
            json
        )
    }

    @Test
    fun `TestRunResult with zero tests serializes to zero-tests pattern`() {
        val result: RunUnityTestsResult = TestRunResult(
            passCount = 0,
            skipCount = 0,
            failCount = 0,
            inconclusiveCount = 0,
            failedTests = emptyList(),
            inconclusiveTests = emptyList()
        )
        val json = Json.encodeToString(result)
        assertEquals(
            """{"success":false,"passCount":0,"skipCount":0,"failCount":0,"inconclusiveCount":0,"failedTests":[],"inconclusiveTests":[]}""",
            json
        )
    }

    @Test
    fun `TestRunResult with all passing serializes to success pattern`() {
        val result: RunUnityTestsResult = TestRunResult(
            passCount = 3,
            skipCount = 1,
            failCount = 0,
            inconclusiveCount = 0,
            failedTests = emptyList(),
            inconclusiveTests = emptyList()
        )
        val json = Json.encodeToString(result)
        assertEquals(
            """{"success":true,"passCount":3,"skipCount":1,"failCount":0,"inconclusiveCount":0,"failedTests":[],"inconclusiveTests":[]}""",
            json
        )
    }
}
