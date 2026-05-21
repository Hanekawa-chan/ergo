package dev.ergo

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Unit tests for the Quick Documentation HTML rendering. */
class ErgoDocHtmlTest {

    @Test
    fun rendersEveryFindingKind() {
        val result = ErgoResult.Success(
            listOf(
                ErgoFunction(
                    name = "F",
                    findings = listOf(
                        ErgoFinding(kind = "sentinel", name = "io.EOF"),
                        ErgoFinding(kind = "type", type = "*os.PathError"),
                        ErgoFinding(kind = "constructed", message = "boom", wrapped = true),
                        ErgoFinding(kind = "unresolved", reason = "interface method call"),
                    ),
                ),
            ),
        )
        val html = ErgoDocHtml.section(result)
        assertTrue(html.contains("class='sections'"))
        assertTrue(html.contains("Errors (ergo)"))
        assertTrue(html.contains("io.EOF"))
        assertTrue(html.contains("*os.PathError"))
        assertTrue(html.contains("boom"))
        assertTrue(html.contains("wraps another error"))
        assertTrue(html.contains("interface method call"))
    }

    @Test
    fun rendersFailureMessage() {
        val html = ErgoDocHtml.section(ErgoResult.Failure("ergo timed out after 30000 ms"))
        assertTrue(html.contains("ergo timed out after 30000 ms"))
    }

    @Test
    fun rendersNoErrorWhenFindingsEmpty() {
        val result = ErgoResult.Success(listOf(ErgoFunction(name = "F", findings = emptyList())))
        assertTrue(ErgoDocHtml.section(result).contains("returns no error"))
    }

    @Test
    fun escapesHtmlInFindingText() {
        val result = ErgoResult.Success(
            listOf(
                ErgoFunction(
                    name = "F",
                    findings = listOf(ErgoFinding(kind = "type", type = "*pkg.Generic[X]")),
                ),
            ),
        )
        val html = ErgoDocHtml.section(result)
        // The bracket pair must survive only in escaped form.
        assertTrue(html.contains("*pkg.Generic[X]"))
        assertFalse(html.contains("<X>"))
    }

    @Test
    fun labelsEachFunctionWhenMultipleMatch() {
        val result = ErgoResult.Success(
            listOf(
                ErgoFunction(
                    name = "Close",
                    recv = "*Reader",
                    findings = listOf(ErgoFinding(kind = "sentinel", name = "io.EOF")),
                ),
                ErgoFunction(
                    name = "Close",
                    recv = "*Writer",
                    findings = listOf(ErgoFinding(kind = "type", type = "*os.PathError")),
                ),
            ),
        )
        val html = ErgoDocHtml.section(result)
        assertTrue(html.contains("(*Reader).Close"))
        assertTrue(html.contains("(*Writer).Close"))
    }

    @Test
    fun linksFindingsThatCarryAPosition() {
        val result = ErgoResult.Success(
            listOf(
                ErgoFunction(
                    name = "F",
                    findings = listOf(
                        ErgoFinding(kind = "sentinel", name = "io.EOF", pos = "/src/io.go:44:5"),
                    ),
                ),
            ),
        )
        val html = ErgoDocHtml.section(result)
        assertTrue("a positioned finding should be a link", html.contains("<a href=\"ergo://"))
        assertTrue(html.contains("io.EOF"))
    }
}
