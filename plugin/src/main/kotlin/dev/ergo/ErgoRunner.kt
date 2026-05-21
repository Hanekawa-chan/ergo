package dev.ergo

import com.intellij.execution.ExecutionException
import com.intellij.execution.configurations.GeneralCommandLine
import com.intellij.execution.process.CapturingProcessHandler
import com.intellij.openapi.progress.ProcessCanceledException
import com.intellij.openapi.progress.ProgressIndicator
import java.io.File
import java.nio.file.Path

/** Runs the bundled `ergo` analyzer as a subprocess. */
object ErgoRunner {
    /** Default analysis timeout; SSA-based analysis can take several seconds. */
    const val DEFAULT_TIMEOUT_MS: Int = 30_000

    /**
     * Analyzes the function named [functionName] declared in [packageDir] — the
     * absolute directory of the source file that declares it.
     *
     * [goBinDir], when given, is prepended to the subprocess PATH so `ergo` can
     * locate the `go` toolchain. When an [indicator] is supplied the run is
     * cancellable; a cancelled run throws [ProcessCanceledException].
     */
    fun analyze(
        functionName: String,
        packageDir: Path,
        goBinDir: String? = null,
        indicator: ProgressIndicator? = null,
        timeoutMs: Int = DEFAULT_TIMEOUT_MS,
    ): ErgoResult {
        val binary = ErgoBinary.path()
            ?: return ErgoResult.Failure(
                "the ergo analyzer is not bundled for this OS/architecture",
            )

        val command = GeneralCommandLine(
            binary.toString(), "errors", "-json", functionName, packageDir.toString(),
        )
            .withWorkDirectory(packageDir.toFile())
            .withParentEnvironmentType(GeneralCommandLine.ParentEnvironmentType.CONSOLE)
        if (goBinDir != null) {
            command.withEnvironment(
                "PATH", goBinDir + File.pathSeparator + System.getenv("PATH").orEmpty(),
            )
        }

        return try {
            val handler = CapturingProcessHandler(command)
            val output = if (indicator != null) {
                handler.runProcessWithProgressIndicator(indicator, timeoutMs)
            } else {
                handler.runProcess(timeoutMs)
            }
            when {
                output.isCancelled -> throw ProcessCanceledException()
                output.isTimeout -> ErgoResult.Failure("ergo timed out after $timeoutMs ms")
                else -> ErgoJson.parse(output.stdout, output.exitCode, output.stderr)
            }
        } catch (e: ExecutionException) {
            ErgoResult.Failure("could not start ergo: ${e.message}")
        }
    }
}
