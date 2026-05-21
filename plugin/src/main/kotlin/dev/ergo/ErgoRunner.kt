package dev.ergo

import com.intellij.execution.ExecutionException
import com.intellij.execution.configurations.GeneralCommandLine
import com.intellij.execution.process.CapturingProcessHandler
import java.nio.file.Path

/**
 * Runs the bundled `ergo` analyzer as a subprocess.
 *
 * This phase inherits the IDE's environment and only sets the working
 * directory; deriving GOROOT/PATH from the project's Go SDK is deferred to a
 * later phase. Until then, analysis works when the `go` toolchain is reachable
 * from the environment the IDE was launched with.
 */
object ErgoRunner {
    /** Default analysis timeout; SSA-based analysis can take several seconds. */
    const val DEFAULT_TIMEOUT_MS: Int = 30_000

    /**
     * Analyzes the function named [functionName] declared in [packageDir] — the
     * absolute directory of the source file that declares it — and reports the
     * errors it can return.
     */
    fun analyze(
        functionName: String,
        packageDir: Path,
        timeoutMs: Int = DEFAULT_TIMEOUT_MS,
    ): ErgoResult {
        val binary = ErgoBinary.path()
            ?: return ErgoResult.Failure(
                "the ergo analyzer is not bundled for this OS/architecture",
            )

        val command = GeneralCommandLine(
            binary.toString(), "errors", "-json", functionName, packageDir.toString(),
        ).withWorkDirectory(packageDir.toFile())

        return try {
            val output = CapturingProcessHandler(command).runProcess(timeoutMs)
            if (output.isTimeout) {
                ErgoResult.Failure("ergo timed out after $timeoutMs ms")
            } else {
                ErgoJson.parse(output.stdout, output.exitCode, output.stderr)
            }
        } catch (e: ExecutionException) {
            ErgoResult.Failure("could not start ergo: ${e.message}")
        }
    }
}
