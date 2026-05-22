package dev.ergo

import com.goide.psi.GoFunctionDeclaration
import com.goide.psi.GoMethodDeclaration
import com.intellij.lang.LanguageExtension
import com.intellij.lang.documentation.DocumentationProvider
import com.intellij.model.Pointer
import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.module.Module
import com.intellij.openapi.module.ModuleUtilCore
import com.intellij.openapi.progress.ProcessCanceledException
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.project.Project
import com.intellij.platform.backend.documentation.DocumentationResult
import com.intellij.platform.backend.documentation.DocumentationTarget
import com.intellij.platform.backend.documentation.PsiDocumentationTargetProvider
import com.intellij.platform.backend.presentation.TargetPresentation
import com.intellij.pom.Navigatable
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiNamedElement
import com.intellij.psi.SmartPointerManager
import java.nio.file.Path

/**
 * Contributes documentation for Go functions and methods. When the hovered
 * element is one it supplies an [ErgoDocumentationTarget]; otherwise it
 * abstains and the platform's normal documentation applies.
 */
class ErgoDocumentationTargetProvider : PsiDocumentationTargetProvider {

    override fun documentationTarget(
        element: PsiElement,
        originalElement: PsiElement?,
    ): DocumentationTarget? {
        if (element !is GoFunctionDeclaration && element !is GoMethodDeclaration) return null
        val name = (element as PsiNamedElement).name?.takeIf { it.isNotEmpty() } ?: return null
        // For a method, the receiver type narrows ergo's by-bare-name results.
        val receiver = (element as? GoMethodDeclaration)?.receiver?.type?.text
        return ErgoDocumentationTarget(element, originalElement, name, receiver, element.project)
    }
}

/**
 * Documentation for one Go function or method: the Go plugin's own
 * documentation with an "Errors (ergo)" section appended.
 *
 * The analyzer runs a subprocess, which must not block under a read action —
 * so the work is done inside [DocumentationResult.asyncDocumentation], the
 * platform's hook for documentation that takes a while to compute. PSI is read
 * under short read actions there; the subprocess runs with no lock held.
 */
class ErgoDocumentationTarget(
    private val element: PsiElement,
    private val originalElement: PsiElement?,
    private val name: String,
    private val receiver: String?,
    val project: Project,
) : DocumentationTarget {

    override fun createPointer(): Pointer<out DocumentationTarget> {
        val elementPtr = SmartPointerManager.createPointer(element)
        val originalPtr = originalElement?.let { SmartPointerManager.createPointer(it) }
        val name = this.name
        val receiver = this.receiver
        return Pointer {
            val element = elementPtr.element ?: return@Pointer null
            ErgoDocumentationTarget(element, originalPtr?.element, name, receiver, element.project)
        }
    }

    override fun computePresentation(): TargetPresentation =
        TargetPresentation.builder(name).presentation()

    override val navigatable: Navigatable? get() = element as? Navigatable

    /** The brief hover info — delegated to the Go provider, no analysis. */
    override fun computeDocumentationHint(): String? =
        ReadAction.compute<String?, RuntimeException> {
            if (element.isValid) {
                delegate { it.getQuickNavigateInfo(element, originalElement) }
            } else {
                null
            }
        }

    override fun computeDocumentation(): DocumentationResult =
        DocumentationResult.asyncDocumentation {
            DocumentationResult.documentation(computeHtml())
        }

    /** Runs outside any read action, so blocking on the analyzer is allowed. */
    private fun computeHtml(): String {
        val context = ReadAction.compute<Context?, RuntimeException> {
            if (element.isValid) collectContext() else null
        } ?: return ""

        val section = try {
            val result = ErgoService.getInstance(project).errorsFor(
                name,
                context.dir,
                context.module,
                ProgressManager.getInstance().progressIndicator,
            )
            ErgoDocHtml.section(result, receiver)
        } catch (e: ProcessCanceledException) {
            throw e
        } catch (e: Exception) {
            LOG.warn("ergo analysis failed for $name", e)
            return context.base.orEmpty()
        }
        return if (context.base.isNullOrBlank()) section else context.base + section
    }

    /** PSI-derived inputs, gathered together under a single read action. */
    private data class Context(val base: String?, val dir: Path, val module: Module?)

    private fun collectContext(): Context? {
        val dir = element.containingFile?.virtualFile?.parent ?: return null
        return Context(
            base = delegate { it.generateDoc(element, originalElement) },
            dir = Path.of(dir.path),
            module = ModuleUtilCore.findModuleForPsiElement(element),
        )
    }

    /** First non-null result of [action] over the Go documentation providers. */
    private inline fun delegate(action: (DocumentationProvider) -> String?): String? {
        for (provider in DOC_PROVIDERS.allForLanguage(element.language)) {
            val result = try {
                action(provider)
            } catch (e: ProcessCanceledException) {
                throw e
            } catch (e: Exception) {
                LOG.warn("delegated provider ${provider.javaClass.name} failed", e)
                null
            }
            if (result != null) return result
        }
        return null
    }

    companion object {
        private val LOG = logger<ErgoDocumentationTarget>()

        /** Every provider registered for the `lang.documentationProvider` EP. */
        private val DOC_PROVIDERS =
            LanguageExtension<DocumentationProvider>("com.intellij.lang.documentationProvider")
    }
}
