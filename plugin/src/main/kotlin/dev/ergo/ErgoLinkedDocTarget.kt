package dev.ergo

import com.intellij.lang.LanguageExtension
import com.intellij.lang.documentation.DocumentationProvider
import com.intellij.model.Pointer
import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.progress.ProcessCanceledException
import com.intellij.openapi.project.Project
import com.intellij.platform.backend.documentation.DocumentationResult
import com.intellij.platform.backend.documentation.DocumentationTarget
import com.intellij.platform.backend.presentation.TargetPresentation
import com.intellij.pom.Navigatable
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiNamedElement
import com.intellij.psi.SmartPointerManager

/**
 * Documentation for a PSI element that was reached by clicking a
 * `psi_element://` link from inside an [ErgoDocumentationTarget] popup — and
 * is not itself a Go function or method, so no "Errors (ergo)" section
 * applies. Renders only the base documentation from the registered Go
 * documentation providers. Exists because the platform's own equivalent
 * target type, `PsiElementDocumentationTarget`, is `@ApiStatus.Internal`.
 */
class ErgoLinkedDocTarget(
    private val element: PsiElement,
    val project: Project,
) : DocumentationTarget {

    override fun createPointer(): Pointer<out DocumentationTarget> {
        val elementPtr = SmartPointerManager.createPointer(element)
        val project = this.project
        return Pointer {
            val element = elementPtr.element ?: return@Pointer null
            ErgoLinkedDocTarget(element, project)
        }
    }

    override fun computePresentation(): TargetPresentation {
        val name = (element as? PsiNamedElement)?.name.orEmpty()
        return TargetPresentation.builder(name).presentation()
    }

    override val navigatable: Navigatable? get() = element as? Navigatable

    override fun computeDocumentationHint(): String? =
        ReadAction.compute<String?, RuntimeException> {
            if (element.isValid) delegateDoc { it.getQuickNavigateInfo(element, null) } else null
        }

    override fun computeDocumentation(): DocumentationResult? {
        val html = ReadAction.compute<String?, RuntimeException> {
            if (element.isValid) delegateDoc { it.generateDoc(element, null) } else null
        }
        return html?.let(DocumentationResult::documentation)
    }

    private inline fun delegateDoc(action: (DocumentationProvider) -> String?): String? {
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

    private companion object {
        private val LOG = logger<ErgoLinkedDocTarget>()
        private val DOC_PROVIDERS =
            LanguageExtension<DocumentationProvider>("com.intellij.lang.documentationProvider")
    }
}