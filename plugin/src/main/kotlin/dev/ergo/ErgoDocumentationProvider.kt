package dev.ergo

import com.goide.psi.GoFunctionDeclaration
import com.goide.psi.GoMethodDeclaration
import com.intellij.lang.LanguageExtension
import com.intellij.lang.documentation.DocumentationProvider
import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.progress.ProcessCanceledException
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiNamedElement
import java.nio.file.Path

/**
 * Augments the Quick Documentation popup for Go functions and methods with an
 * "Errors (ergo)" section listing the concrete errors they can return.
 *
 * Registered with `order="first"`, so it runs ahead of the Go plugin's own
 * documentation provider; it delegates to that provider for the base content
 * and appends the analyzer's findings.
 */
class ErgoDocumentationProvider : DocumentationProvider {

    private data class Target(val name: String, val dir: Path)

    override fun generateDoc(element: PsiElement, originalElement: PsiElement?): String? {
        // PSI access happens under a read action; the analyzer subprocess runs
        // afterwards, with no read lock held, so it cannot stall write actions.
        val base = ReadAction.compute<String?, RuntimeException> {
            baseDoc(element, originalElement)
        }
        val target = ReadAction.compute<Target?, RuntimeException> {
            ergoTarget(element)
        } ?: return base

        val section = try {
            ErgoDocHtml.section(ErgoRunner.analyze(target.name, target.dir))
        } catch (e: ProcessCanceledException) {
            throw e
        } catch (e: Exception) {
            LOG.warn("ergo analysis failed for ${target.name}", e)
            return base
        }
        return if (base.isNullOrBlank()) section else base + section
    }

    /** Delegates to the other Go documentation providers for the base content. */
    private fun baseDoc(element: PsiElement, originalElement: PsiElement?): String? {
        for (provider in DOC_PROVIDERS.allForLanguage(element.language)) {
            if (provider === this) continue
            try {
                provider.generateDoc(element, originalElement)?.let { return it }
            } catch (e: ProcessCanceledException) {
                throw e
            } catch (e: Exception) {
                LOG.warn("delegated documentation provider ${provider.javaClass.name} failed", e)
            }
        }
        return null
    }

    /** The analysis target if [element] is a Go function or method, else null. */
    private fun ergoTarget(element: PsiElement): Target? {
        if (element !is GoFunctionDeclaration && element !is GoMethodDeclaration) return null
        val name = (element as PsiNamedElement).name?.takeIf { it.isNotEmpty() } ?: return null
        val dir = element.containingFile?.virtualFile?.parent ?: return null
        return Target(name, Path.of(dir.path))
    }

    private companion object {
        private val LOG = logger<ErgoDocumentationProvider>()

        /** Every provider registered for the `lang.documentationProvider` EP. */
        private val DOC_PROVIDERS =
            LanguageExtension<DocumentationProvider>("com.intellij.lang.documentationProvider")
    }
}
