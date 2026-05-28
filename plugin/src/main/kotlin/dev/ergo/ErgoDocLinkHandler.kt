package dev.ergo

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.fileEditor.OpenFileDescriptor
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.platform.backend.documentation.DocumentationLinkHandler
import com.intellij.platform.backend.documentation.DocumentationTarget
import com.intellij.platform.backend.documentation.LinkResolveResult

/**
 * Handles clicks in the Quick Documentation popup whose target is an
 * [ErgoDocumentationTarget]. `ergo://` links navigate the editor to where an
 * error originates; everything else is forwarded to the platform's standard
 * PSI link handler via [ErgoPsiLinkBridge] — which would otherwise ignore the
 * click, because that handler only acts on its own internal target type, so
 * without this delegation every link in the wrapped Go-plugin documentation
 * would be dead.
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

        // Hand the hovered element to the standard PSI link handler — same
        // context the Go provider used to render the base documentation, so
        // its `psi_element://` and external-doc links resolve as they normally
        // would.
        val element = ergoTarget.element
        if (!element.isValid) return null
        return ErgoPsiLinkBridge.resolveAsPsi(ergoTarget.project, element, url)
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
}