package com.nowsprinting.mcp_extension_for_unity

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
}
