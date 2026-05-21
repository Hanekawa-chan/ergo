package dev.ergo

import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.module.Module
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.project.Project
import com.intellij.psi.util.PsiModificationTracker
import java.nio.file.Path

/**
 * Project-level entry point to the `ergo` analyzer: resolves the Go SDK,
 * runs the analysis, and caches successful results.
 *
 * Analysis depends on a function's whole transitive callee graph, so the cache
 * is keyed by the project-wide PSI modification count: any Go edit invalidates
 * every entry. Only successes are cached — failures (timeouts especially) are
 * retried on the next request.
 */
@Service(Service.Level.PROJECT)
class ErgoService(private val project: Project) {

    private data class Key(val name: String, val dir: String)

    private val lock = Any()
    private var stamp = -1L
    private val cache = HashMap<Key, ErgoResult.Success>()

    /**
     * Reports the errors the function named [functionName] in [packageDir] can
     * return, serving a cached result when one is still valid.
     */
    fun errorsFor(
        functionName: String,
        packageDir: Path,
        module: Module?,
        indicator: ProgressIndicator?,
    ): ErgoResult {
        val key = Key(functionName, packageDir.toString())
        val current = PsiModificationTracker.getInstance(project).modificationCount

        synchronized(lock) {
            if (current != stamp) {
                cache.clear()
                stamp = current
            }
            cache[key]?.let { return it }
        }

        val goBinDir = ErgoGoSdk.goBinDir(project, module)
        val result = ErgoRunner.analyze(functionName, packageDir, goBinDir, indicator)

        if (result is ErgoResult.Success) {
            synchronized(lock) {
                if (current == stamp) cache[key] = result
            }
        }
        return result
    }

    companion object {
        fun getInstance(project: Project): ErgoService = project.service()
    }
}
