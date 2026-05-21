package dev.ergo

import com.goide.sdk.GoSdkService
import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.module.Module
import com.intellij.openapi.progress.ProcessCanceledException
import com.intellij.openapi.project.Project

/**
 * Derives the directory of the `go` executable from the project's Go SDK.
 *
 * `ergo` shells out to the `go` toolchain; putting this directory on the
 * analyzer subprocess's PATH lets it find `go` even when the IDE was launched
 * without the toolchain on the inherited environment. Best-effort: any failure
 * (no SDK configured, Go-plugin API change) yields null, and the subprocess
 * falls back to the inherited environment.
 */
object ErgoGoSdk {
    private val LOG = logger<ErgoGoSdk>()

    /** The directory containing `go` for [module]'s SDK, or null if unavailable. */
    fun goBinDir(project: Project, module: Module?): String? = try {
        val sdk = GoSdkService.getInstance(project).getSdk(module)
        if (sdk.isValid) sdk.executable?.parent?.path else null
    } catch (e: ProcessCanceledException) {
        throw e
    } catch (e: Exception) {
        LOG.debug("could not resolve the Go SDK", e)
        null
    }
}
