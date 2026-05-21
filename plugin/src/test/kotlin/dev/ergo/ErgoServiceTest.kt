package dev.ergo

import com.intellij.testFramework.fixtures.BasePlatformTestCase
import java.nio.file.Files
import java.nio.file.Path

/**
 * Integration test: drives [ErgoService] end to end against a real on-disk Go
 * module, exercising the bundled binary, the subprocess, JSON parsing, and the
 * result cache. Requires the `go` toolchain on PATH (always true under the
 * Gradle build).
 */
class ErgoServiceTest : BasePlatformTestCase() {

    private lateinit var moduleDir: Path

    override fun setUp() {
        super.setUp()
        moduleDir = Files.createTempDirectory("ergo-it")
        Files.writeString(moduleDir.resolve("go.mod"), "module ergotest\n\ngo 1.21\n")
        Files.writeString(
            moduleDir.resolve("errs.go"),
            """
            package ergotest

            import "errors"

            var ErrBoom = errors.New("boom")

            func Fail() error {
            	return ErrBoom
            }
            """.trimIndent() + "\n",
        )
    }

    override fun tearDown() {
        try {
            moduleDir.toFile().deleteRecursively()
        } finally {
            super.tearDown()
        }
    }

    fun testAnalyzesRealModule() {
        val result = ErgoService.getInstance(project).errorsFor("Fail", moduleDir, null, null)

        assertTrue("expected Success, got $result", result is ErgoResult.Success)
        val functions = (result as ErgoResult.Success).functions
        assertEquals(1, functions.size)

        val findings = functions[0].findings.orEmpty()
        assertEquals(1, findings.size)
        assertEquals("sentinel", findings[0].kind)
        assertEquals("ergotest.ErrBoom", findings[0].name)
    }

    fun testCachesSuccessfulResult() {
        val service = ErgoService.getInstance(project)
        val first = service.errorsFor("Fail", moduleDir, null, null)
        val second = service.errorsFor("Fail", moduleDir, null, null)

        assertTrue(first is ErgoResult.Success)
        assertSame("the second call should be served from the cache", first, second)
    }
}
