package dev.ergo

import com.goide.psi.GoFunctionDeclaration
import com.goide.psi.GoMethodDeclaration
import com.intellij.lang.LanguageExtension
import com.intellij.lang.documentation.DocumentationProvider
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.fileEditor.OpenFileDescriptor
import com.intellij.openapi.progress.ProcessCanceledException
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.platform.backend.documentation.DocumentationLinkHandler
import com.intellij.platform.backend.documentation.DocumentationTarget
import com.intellij.platform.backend.documentation.LinkResolveResult
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiNamedElement
import java.net.URLDecoder
import java.nio.charset.StandardCharsets

/**
 * Handles clicks in the Quick Documentation popup whose target is an
 * [ErgoDocumentationTarget]. `ergo://` links navigate the editor to where an
 * error originates; `psi_element://` links — the ones used by the wrapped
 * Go-plugin documentation — are resolved here and the popup navigates to the
 * linked element. Without this, the platform's own PSI link handler ignores
 * the click (it only operates on its own internal target type), and every
 * link in the base documentation would be dead.
 */
class ErgoDocLinkHandler : DocumentationLinkHandler {

    override fun resolveLink(target: DocumentationTarget, url: String): LinkResolveResult? {
        val ergoTarget = target as? ErgoDocumentationTarget ?: return null

        val location = ErgoDocLinks.parse(url)
        if (location != null) {
            navigateEditor(ergoTarget, location)
            // Navigating the editor is the whole effect; leave the popup unchanged.
            return null
        }

        if (!url.startsWith(PSI_ELEMENT_PREFIX)) return null
        val context = ergoTarget.element.takeIf { it.isValid } ?: return null
        val ref = URLDecoder.decode(url.removePrefix(PSI_ELEMENT_PREFIX), StandardCharsets.UTF_8)
        val resolved = resolvePsiLink(context, ref) ?: return null
        return LinkResolveResult.resolvedTarget(buildTarget(resolved, ergoTarget.project))
    }

    /** First non-null `getDocumentationElementForLink` across all providers for the context's language. */
    private fun resolvePsiLink(context: PsiElement, ref: String): PsiElement? {
        for (provider in DOC_PROVIDERS.allForLanguage(context.language)) {
            val result = try {
                provider.getDocumentationElementForLink(context.manager, ref, context)
            } catch (e: ProcessCanceledException) {
                throw e
            } catch (e: Exception) {
                LOG.warn("link resolution by ${provider.javaClass.name} failed", e)
                null
            }
            if (result != null) return result
        }
        return null
    }

    /**
     * A Go function or method gets the full [ErgoDocumentationTarget] (so the
     * "Errors (ergo)" section follows the click); anything else just shows
     * the Go plugin's base documentation via [ErgoLinkedDocTarget].
     */
    private fun buildTarget(resolved: PsiElement, project: com.intellij.openapi.project.Project): DocumentationTarget {
        if (resolved is GoFunctionDeclaration || resolved is GoMethodDeclaration) {
            val name = (resolved as PsiNamedElement).name?.takeIf { it.isNotEmpty() }
            if (name != null) {
                val receiver = (resolved as? GoMethodDeclaration)?.receiver?.type?.text
                return ErgoDocumentationTarget(resolved, null, name, receiver, project)
            }
        }
        return ErgoLinkedDocTarget(resolved, project)
    }

    private fun navigateEditor(target: ErgoDocumentationTarget, location: ErgoDocLinks.Location) {
        // Resolve the file off the EDT (may refresh the VFS), then navigate on it.
        ApplicationManager.getApplication().executeOnPooledThread {
            val file = LocalFileSystem.getInstance().refreshAndFindFileByPath(location.path)
            if (file != null && !target.project.isDisposed) {
                ApplicationManager.getApplication().invokeLater(
                    { OpenFileDescriptor(target.project, file, location.line, location.column).navigate(true) },
                    target.project.disposed,
                )
            }
        }
    }

    private companion object {
        private const val PSI_ELEMENT_PREFIX = "psi_element://"
        private val LOG = logger<ErgoDocLinkHandler>()
        private val DOC_PROVIDERS =
            LanguageExtension<DocumentationProvider>("com.intellij.lang.documentationProvider")
    }
}