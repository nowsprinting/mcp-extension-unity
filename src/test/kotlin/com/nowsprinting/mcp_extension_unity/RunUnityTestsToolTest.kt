package com.nowsprinting.mcp_extension_unity

import com.nowsprinting.mcp_extension_unity.model.McpTestMode
import com.nowsprinting.mcp_extension_unity.model.McpTestResultItem
import com.nowsprinting.mcp_extension_unity.model.McpTestResultStatus
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RunUnityTestsToolTest {

    @Test
    fun `playModeRejectionMessage - isPlaying true returns error message`() {
        val message = RunUnityTestsTool.playModeRejectionMessage(isPlaying = true)
        assertNotNull("Expected a non-null rejection message when Editor is playing", message)
        assertTrue("Message should mention unity_play_control", message!!.contains("unity_play_control"))
    }

    @Test
    fun `playModeRejectionMessage - isPlaying false returns null`() {
        assertNull(RunUnityTestsTool.playModeRejectionMessage(isPlaying = false))
    }

    @Test
    fun `parseTestMode - editmode returns EditMode`() {
        assertEquals(McpTestMode.EditMode, RunUnityTestsTool.parseTestMode("editmode"))
    }

    @Test
    fun `parseTestMode - edit returns EditMode`() {
        assertEquals(McpTestMode.EditMode, RunUnityTestsTool.parseTestMode("edit"))
    }

    @Test
    fun `parseTestMode - EditMode returns EditMode`() {
        assertEquals(McpTestMode.EditMode, RunUnityTestsTool.parseTestMode("EditMode"))
    }

    @Test
    fun `parseTestMode - playmode returns PlayMode`() {
        assertEquals(McpTestMode.PlayMode, RunUnityTestsTool.parseTestMode("playmode"))
    }

    @Test
    fun `parseTestMode - play returns PlayMode`() {
        assertEquals(McpTestMode.PlayMode, RunUnityTestsTool.parseTestMode("play"))
    }

    @Test
    fun `parseTestMode - PlayMode returns PlayMode`() {
        assertEquals(McpTestMode.PlayMode, RunUnityTestsTool.parseTestMode("PlayMode"))
    }

    @Test
    fun `parseTestMode - invalid value returns null`() {
        assertNull(RunUnityTestsTool.parseTestMode("invalid"))
    }

    @Test
    fun `parseTestMode - empty string returns null`() {
        assertNull(RunUnityTestsTool.parseTestMode(""))
    }

    @Test
    fun `parseTestMode - blank string returns null`() {
        assertNull(RunUnityTestsTool.parseTestMode("   "))
    }

    @Test
    fun `parseTestMode - null returns null`() {
        assertNull(RunUnityTestsTool.parseTestMode(null))
    }

    @Test
    fun `sanitizeAssemblyNames - null returns empty list`() {
        val result = RunUnityTestsTool.sanitizeAssemblyNames(null)
        assertTrue(result.isEmpty())
    }

    @Test
    fun `sanitizeAssemblyNames - empty list returns empty list`() {
        val result = RunUnityTestsTool.sanitizeAssemblyNames(emptyList())
        assertTrue(result.isEmpty())
    }

    @Test
    fun `sanitizeAssemblyNames - list with only blank strings returns empty`() {
        val result = RunUnityTestsTool.sanitizeAssemblyNames(listOf("", "   ", "\t"))
        assertTrue(result.isEmpty())
    }

    @Test
    fun `sanitizeAssemblyNames - valid names are preserved`() {
        val result = RunUnityTestsTool.sanitizeAssemblyNames(listOf("MyTests.EditMode", "MyTests.PlayMode"))
        assertEquals(listOf("MyTests.EditMode", "MyTests.PlayMode"), result)
    }

    @Test
    fun `sanitizeAssemblyNames - blank strings are filtered out`() {
        val result = RunUnityTestsTool.sanitizeAssemblyNames(listOf("", "  "))
        assertTrue(result.isEmpty())
    }

    @Test
    fun `sanitizeAssemblyNames - mixed valid and blank strings keeps only valid`() {
        val result = RunUnityTestsTool.sanitizeAssemblyNames(listOf("", "MyTests.EditMode", "  ", "MyTests.PlayMode"))
        assertEquals(listOf("MyTests.EditMode", "MyTests.PlayMode"), result)
    }

    // normalizeTestNames tests

    @Test
    fun `normalizeTestNames - null returns empty list`() {
        assertTrue(RunUnityTestsTool.normalizeTestNames(null).isEmpty())
    }

    @Test
    fun `normalizeTestNames - empty list returns empty list`() {
        assertTrue(RunUnityTestsTool.normalizeTestNames(emptyList()).isEmpty())
    }

    @Test
    fun `normalizeTestNames - ASCII input is unchanged`() {
        val ascii = "MyNamespace.MyClass.MyMethod"
        assertEquals(listOf(ascii), RunUnityTestsTool.normalizeTestNames(listOf(ascii)))
    }

    @Test
    fun `normalizeTestNames - NFC dakuten input is unchanged`() {
        // U+3067 = NFC "で" (precomposed)
        val nfc = "で"
        assertEquals(listOf(nfc), RunUnityTestsTool.normalizeTestNames(listOf(nfc)))
    }

    @Test
    fun `normalizeTestNames - NFD dakuten is normalized to NFC`() {
        // NFD "で" = U+3066 (て) + U+3099 (combining dakuten)
        // NFC "で" = U+3067
        val nfd = "で"
        val nfc = "で"
        assertEquals(listOf(nfc), RunUnityTestsTool.normalizeTestNames(listOf(nfd)))
    }

    @Test
    fun `normalizeTestNames - NFD handakuten is normalized to NFC`() {
        // NFD "パ" = U+30CF (ハ) + U+309A (combining handakuten)
        // NFC "パ" = U+30D1
        val nfd = "パ"
        val nfc = "パ"
        assertEquals(listOf(nfc), RunUnityTestsTool.normalizeTestNames(listOf(nfd)))
    }

    @Test
    fun `normalizeTestNames - full FQN with NFD dakuten chars is normalized`() {
        // NFD forms using codepoints from M-1 verification:
        // "で" = U+3066 + U+3099, "が" = U+304B + U+3099, "パ" = U+30CF + U+309A
        val nfd = "Ns.Class.Method_で_が_パ"
        val nfc = "Ns.Class.Method_で_が_パ"
        assertEquals(listOf(nfc), RunUnityTestsTool.normalizeTestNames(listOf(nfd)))
    }

    @Test
    fun `normalizeTestNames - mixed list normalizes NFD entries and preserves NFC and ASCII`() {
        // NFD "で" = U+3066 + U+3099, NFC "で" = U+3067
        val input = listOf("AsciiTest", "で", "で")
        val expected = listOf("AsciiTest", "で", "で")
        assertEquals(expected, RunUnityTestsTool.normalizeTestNames(input))
    }

    @Test
    fun `TestErrorResult serializes to error pattern`() {
        val result: RunUnityTestsResult = TestErrorResult(errorMessage = "Some infrastructure error")
        val json = Json.encodeToString(result)
        assertEquals("""{"success":false,"errorMessage":"Some infrastructure error"}""", json)
    }

    @Test
    fun `TestRunResult with failures serializes to failure pattern`() {
        val result: RunUnityTestsResult = TestRunResult(
            passCount = 2,
            failCount = 1,
            inconclusiveCount = 0,
            skipCount = 0,
            failedTests = listOf(TestDetail(testId = "MyTest", output = "Expected true but was false", duration = 100)),
            inconclusiveTests = emptyList()
        )
        val json = Json.encodeToString(result)
        assertEquals(
            """{"success":false,"passCount":2,"failCount":1,"inconclusiveCount":0,"skipCount":0,"failedTests":[{"testId":"MyTest","output":"Expected true but was false","duration":100}],"inconclusiveTests":[]}""",
            json
        )
    }

    @Test
    fun `TestRunResult with inconclusives serializes to failure pattern`() {
        val result: RunUnityTestsResult = TestRunResult(
            passCount = 2,
            failCount = 0,
            inconclusiveCount = 1,
            skipCount = 0,
            failedTests = emptyList(),
            inconclusiveTests = listOf(TestDetail(testId = "MyTest", output = "Expected true but was false", duration = 100))
        )
        val json = Json.encodeToString(result)
        assertEquals(
            """{"success":false,"passCount":2,"failCount":0,"inconclusiveCount":1,"skipCount":0,"failedTests":[],"inconclusiveTests":[{"testId":"MyTest","output":"Expected true but was false","duration":100}]}""",
            json
        )
    }

    @Test
    fun `TestRunResult with zero tests serializes to zero-tests pattern`() {
        val result: RunUnityTestsResult = TestRunResult(
            passCount = 0,
            failCount = 0,
            inconclusiveCount = 0,
            skipCount = 0,
            failedTests = emptyList(),
            inconclusiveTests = emptyList()
        )
        val json = Json.encodeToString(result)
        assertEquals(
            """{"success":false,"passCount":0,"failCount":0,"inconclusiveCount":0,"skipCount":0,"failedTests":[],"inconclusiveTests":[]}""",
            json
        )
    }

    @Test
    fun `TestRunResult with all passing serializes to success pattern`() {
        val result: RunUnityTestsResult = TestRunResult(
            passCount = 3,
            failCount = 0,
            inconclusiveCount = 0,
            skipCount = 1,
            failedTests = emptyList(),
            inconclusiveTests = emptyList()
        )
        val json = Json.encodeToString(result)
        assertEquals(
            """{"success":true,"passCount":3,"failCount":0,"inconclusiveCount":0,"skipCount":1,"failedTests":[],"inconclusiveTests":[]}""",
            json
        )
    }

    // filterLeafResults tests

    private fun item(
        testId: String,
        parentId: String = "",
        status: McpTestResultStatus = McpTestResultStatus.Success,
        output: String = "",
        duration: Int = 0
    ) = McpTestResultItem(testId, parentId, output, duration, status)

    @Test
    fun `filterLeafResults - leaf only - all counted`() {
        val results = listOf(
            item("test1", status = McpTestResultStatus.Success),
            item("test2", status = McpTestResultStatus.Failure, output = "fail msg"),
            item("test3", status = McpTestResultStatus.Ignored),
            item("test4", status = McpTestResultStatus.Inconclusive, output = "inconc msg"),
        )
        val actual = RunUnityTestsTool.filterLeafResults(results)
        assertEquals(1, actual.passCount)
        assertEquals(1, actual.failCount)
        assertEquals(1, actual.skipCount)
        assertEquals(1, actual.inconclusiveCount)
        assertEquals(listOf("test2"), actual.failedTests.map { it.testId })
        assertEquals(listOf("test4"), actual.inconclusiveTests.map { it.testId })
    }

    @Test
    fun `filterLeafResults - parent excluded when children present`() {
        // parent -> child1, child2
        val results = listOf(
            item("parent", status = McpTestResultStatus.Success),
            item("child1", parentId = "parent", status = McpTestResultStatus.Success),
            item("child2", parentId = "parent", status = McpTestResultStatus.Success),
        )
        val actual = RunUnityTestsTool.filterLeafResults(results)
        assertEquals(2, actual.passCount)
        assertEquals(0, actual.failCount)
    }

    @Test
    fun `filterLeafResults - parameterized test parent excluded, children counted`() {
        // parameterized: parent + 3 children
        val results = listOf(
            item("ParamTest", status = McpTestResultStatus.Success),
            item("ParamTest(0)", parentId = "ParamTest", status = McpTestResultStatus.Success),
            item("ParamTest(1)", parentId = "ParamTest", status = McpTestResultStatus.Failure, output = "fail"),
            item("ParamTest(2)", parentId = "ParamTest", status = McpTestResultStatus.Success),
        )
        val actual = RunUnityTestsTool.filterLeafResults(results)
        assertEquals(2, actual.passCount)
        assertEquals(1, actual.failCount)
        assertEquals(0, actual.skipCount)
        assertEquals(listOf("ParamTest(1)"), actual.failedTests.map { it.testId })
    }

    @Test
    fun `filterLeafResults - empty list returns all zeros`() {
        val actual = RunUnityTestsTool.filterLeafResults(emptyList())
        assertEquals(0, actual.passCount)
        assertEquals(0, actual.failCount)
        assertEquals(0, actual.skipCount)
        assertEquals(0, actual.inconclusiveCount)
        assertTrue(actual.failedTests.isEmpty())
        assertTrue(actual.inconclusiveTests.isEmpty())
    }

    @Test
    fun `filterLeafResults - blank parentId treated as leaf`() {
        // parentId = "" means no parent → treat as leaf
        val results = listOf(
            item("test1", parentId = "", status = McpTestResultStatus.Success),
            item("test2", parentId = "   ", status = McpTestResultStatus.Failure),
        )
        val actual = RunUnityTestsTool.filterLeafResults(results)
        assertEquals(1, actual.passCount)
        assertEquals(1, actual.failCount)
    }
}
