package dev.ergo

import com.intellij.ide.plugins.PluginManagerCore
import com.intellij.openapi.application.PathManager
import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.extensions.PluginId
import com.intellij.openapi.util.SystemInfo
import com.intellij.util.system.CpuArch
import java.nio.file.FileAlreadyExistsException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption

/**
 * Locates the bundled `ergo` analyzer binary for the running OS/architecture.
 *
 * The cross-compiled binaries ride along in the plugin jar under `/bin`. On
 * first use the one matching this platform is extracted to a version-stamped
 * directory under the IDE system path and marked executable.
 */
object ErgoBinary {
    private val LOG = logger<ErgoBinary>()
    private const val PLUGIN_ID = "dev.ergo.intellij"

    /**
     * Returns the path to a runnable `ergo` binary for this platform, extracting
     * it on first call, or `null` if no binary is bundled for this OS/arch.
     */
    @Synchronized
    fun path(): Path? {
        val name = binaryName() ?: return null
        val target = cacheDir().resolve(name)
        if (Files.isRegularFile(target)) return target

        val stream = ErgoBinary::class.java.getResourceAsStream("/bin/$name")
        if (stream == null) {
            LOG.warn("bundled ergo binary /bin/$name is missing from the plugin")
            return null
        }
        stream.use { input ->
            Files.createDirectories(target.parent)
            val tmp = Files.createTempFile(target.parent, "ergo", ".tmp")
            Files.copy(input, tmp, StandardCopyOption.REPLACE_EXISTING)
            tmp.toFile().setExecutable(true, /* ownerOnly = */ false)
            try {
                Files.move(tmp, target, StandardCopyOption.ATOMIC_MOVE)
            } catch (_: FileAlreadyExistsException) {
                Files.deleteIfExists(tmp) // another thread/process won the race
            }
        }
        return target
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

    /** A version-stamped directory, so a plugin update re-extracts the binary. */
    private fun cacheDir(): Path {
        val version = PluginManagerCore.getPlugin(PluginId.getId(PLUGIN_ID))?.version ?: "dev"
        return PathManager.getSystemDir().resolve("ergo").resolve(version)
    }
}
