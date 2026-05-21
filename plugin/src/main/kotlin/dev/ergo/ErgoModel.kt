package dev.ergo

import com.google.gson.Gson
import com.google.gson.JsonSyntaxException

/**
 * One error a Go function can return — mirrors a `finding` object in the JSON
 * emitted by `ergo errors -json`. Optional fields are absent when empty.
 */
data class ErgoFinding(
    /** "sentinel", "type", "constructed", or "unresolved". */
    val kind: String,
    val type: String? = null,
    val name: String? = null,
    val message: String? = null,
    val wrapped: Boolean = false,
    val reason: String? = null,
    val pos: String? = null,
)

/** A Go function or method analyzed by `ergo`, with the errors it can return. */
data class ErgoFunction(
    val name: String,
    val recv: String? = null,
    val pos: String? = null,
    val findings: List<ErgoFinding>? = null,
)

/** The top-level JSON object emitted by `ergo errors -json`. */
private data class ErgoResponse(
    val functions: List<ErgoFunction>? = null,
    val error: String? = null,
)

/** Outcome of an `ergo errors` run. */
sealed interface ErgoResult {
    /** The analyzer succeeded; [functions] holds one entry per matched function. */
    data class Success(val functions: List<ErgoFunction>) : ErgoResult

    /** The analyzer failed, timed out, or could not be run; [message] explains why. */
    data class Failure(val message: String) : ErgoResult
}

/** Parses the output of an `ergo errors -json` run into an [ErgoResult]. */
object ErgoJson {
    private val gson = Gson()

    /**
     * Interprets the [stdout], [exitCode], and [stderr] of an `ergo errors
     * -json` invocation. `ergo` writes a single JSON object to stdout in both
     * the success and failure cases, so stdout is the primary input; [stderr]
     * is only used when stdout is empty or unusable.
     */
    fun parse(stdout: String, exitCode: Int, stderr: String = ""): ErgoResult {
        val body = stdout.trim()
        if (body.isEmpty()) {
            return ErgoResult.Failure(
                stderr.ifBlank { "ergo produced no output (exit code $exitCode)" },
            )
        }
        val response = try {
            gson.fromJson(body, ErgoResponse::class.java)
        } catch (e: JsonSyntaxException) {
            return ErgoResult.Failure("could not parse ergo output: ${e.message}")
        } ?: return ErgoResult.Failure("ergo produced empty output")

        response.error?.let { return ErgoResult.Failure(it) }
        val functions = response.functions
            ?: return ErgoResult.Failure(
                stderr.ifBlank { "ergo returned no result (exit code $exitCode)" },
            )
        return ErgoResult.Success(functions)
    }
}
