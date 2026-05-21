package dev.ergo

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Unit tests for parsing the `ergo errors -json` output schema. */
class ErgoModelTest {

    @Test
    fun parsesFunctionsWithFindings() {
        val json = """
            {"functions":[
              {"name":"FindFunction","pos":"search.go:33:6","findings":[
                {"kind":"sentinel","type":"error","name":"io.EOF","pos":"io.go:44:5"},
                {"kind":"constructed","message":"parse %s: %w","wrapped":true,"pos":"x.go:1:1"}
              ]}
            ]}
        """.trimIndent()

        val result = ErgoJson.parse(json, exitCode = 0)
        assertTrue("expected Success, got $result", result is ErgoResult.Success)
        val functions = (result as ErgoResult.Success).functions
        assertEquals(1, functions.size)
        assertEquals("FindFunction", functions[0].name)

        val findings = functions[0].findings!!
        assertEquals(2, findings.size)
        assertEquals("sentinel", findings[0].kind)
        assertEquals("io.EOF", findings[0].name)
        assertEquals(false, findings[0].wrapped)
        assertEquals("constructed", findings[1].kind)
        assertTrue("expected wrapped finding", findings[1].wrapped)
    }

    @Test
    fun parsesErrorObjectAsFailure() {
        val result = ErgoJson.parse("""{"error":"no function \"X\" in pkg"}""", exitCode = 1)
        assertTrue(result is ErgoResult.Failure)
        assertEquals("no function \"X\" in pkg", (result as ErgoResult.Failure).message)
    }

    @Test
    fun functionWithNoErrors() {
        val result = ErgoJson.parse("""{"functions":[{"name":"F","findings":[]}]}""", exitCode = 0)
        assertTrue(result is ErgoResult.Success)
        assertEquals(0, (result as ErgoResult.Success).functions[0].findings!!.size)
    }

    @Test
    fun emptyOutputFallsBackToStderr() {
        val result = ErgoJson.parse(stdout = "", exitCode = 1, stderr = "ergo: boom")
        assertTrue(result is ErgoResult.Failure)
        assertEquals("ergo: boom", (result as ErgoResult.Failure).message)
    }

    @Test
    fun garbageOutputIsFailure() {
        assertTrue(ErgoJson.parse("not json at all", exitCode = 0) is ErgoResult.Failure)
    }
}
