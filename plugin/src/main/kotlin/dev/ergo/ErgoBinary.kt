package dev.ergo

import com.intellij.openapi.application.PathManager
import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.util.SystemInfo
import com.intellij.util.system.CpuArch
import java.nio.file.FileAlreadyExistsException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import java.util.concurrent.TimeUnit

/**
 * Locates the bundled `ergo` analyzer binary for the running OS/architecture.
 *
 * The cross-compiled binaries ride along in the plugin jar under `/bin`. On
 * first use the one matching this platform is extracted to a directory keyed
 * by its content hash under the IDE system path, marked executable, and — on
 * macOS — cleared of the quarantine flag so Gatekeeper does not block it.
 */
object ErgoBinary {
    private val LOG = logger<ErgoBinary>()

    /** The extracted binary, resolved once per session. */
    private var cached: Path? = null

    /**
     * Returns the path to a runnable `ergo` binary for this platform, extracting
     * it on first call, or `null` if no binary is bundled for this OS/arch.
     */
    @Synchronized
    fun path(): Path? {
        cached?.let { return it }

        val name = binaryName() ?: return null
        val bytes = ErgoBinary::class.java.getResourceAsStream("/bin/$name")?.use { it.readBytes() }
        if (bytes == null) {
            LOG.warn("bundled ergo binary /bin/$name is missing from the plugin")
            return null
        }

        // The cache directory is keyed by the binary's content hash: a plugin
        // update that ships a new binary lands in a fresh directory and is
        // re-extracted, while an unchanged binary is reused as-is.
        val target = PathManager.getSystemDir()
            .resolve("ergo").resolve(contentKey(bytes)).resolve(name)
        if (!Files.isRegularFile(target)) {
            extract(bytes, target)
        }
        cached = target
        return target
    }

    /** Writes [bytes] to [target] as an executable file, via a temp file. */
    private fun extract(bytes: ByteArray, target: Path) {
        Files.createDirectories(target.parent)
        val tmp = Files.createTempFile(target.parent, "ergo", ".tmp")
        Files.write(tmp, bytes)
        tmp.toFile().setExecutable(true, /* ownerOnly = */ false)
        clearQuarantine(tmp)
        try {
            Files.move(tmp, target, StandardCopyOption.ATOMIC_MOVE)
        } catch (_: FileAlreadyExistsException) {
            Files.deleteIfExists(tmp) // another thread/process won the race
        }
    }

    /** A short hex digest of [bytes], used to name the cache directory. */
    private fun contentKey(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes)
            .take(8)
            .joinToString("") { "%02x".format(it.toInt() and 0xFF) }

    /**
     * Strips every extended attribute — most importantly `com.apple.quarantine`
     * — from [file] on macOS. The bundled binaries are unsigned; with the
     * quarantine flag set Gatekeeper refuses to run them ("cannot be opened
     * because the developer cannot be verified"). A failure here is non-fatal:
     * the binary may still run, so the extraction is allowed to proceed.
     */
    private fun clearQuarantine(file: Path) {
        if (!SystemInfo.isMac) return
        try {
            val process = ProcessBuilder("/usr/bin/xattr", "-c", file.toString())
                .redirectOutput(ProcessBuilder.Redirect.DISCARD)
                .redirectError(ProcessBuilder.Redirect.DISCARD)
                .start()
            when {
                !process.waitFor(5, TimeUnit.SECONDS) -> {
                    process.destroyForcibly()
                    LOG.warn("xattr timed out clearing quarantine on $file")
                }
                process.exitValue() != 0 ->
                    LOG.warn("xattr exited ${process.exitValue()} clearing quarantine on $file")
            }
        } catch (e: Exception) {
            LOG.warn("could not clear quarantine on $file", e)
            if (e is InterruptedException) Thread.currentThread().interrupt()
        }
    }

    /** The bundled binary file name for this platform, e.g. `ergo-linux-amd64`. */
    private fun binaryName(): String? {
        val os = when {
            SystemInfo.isWindows -> "windows"
            SystemInfo.isMac -> "darwin"
            SystemInfo.isLinux -> "linux"
            else -> return null
        }
        val arch = when (CpuArch.CURRENT) {
            CpuArch.X86_64 -> "amd64"
            CpuArch.ARM64 -> "arm64"
            else -> return null
        }
        val ext = if (os == "windows") ".exe" else ""
        return "ergo-$os-$arch$ext"
    }
}
