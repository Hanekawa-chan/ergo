package dev.ergo

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.fileEditor.OpenFileDescriptor
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.platform.backend.documentation.DocumentationLinkHandler
import com.intellij.platform.backend.documentation.DocumentationTarget
import com.intellij.platform.backend.documentation.LinkResolveResult

/**
 * Handles clicks on the error links in the "Errors (ergo)" documentation
 * section, navigating the editor to where each error originates.
 */
class ErgoDocLinkHandler : DocumentationLinkHandler {

    override fun resolveLink(target: DocumentationTarget, url: String): LinkResolveResult? {
        val location = ErgoDocLinks.parse(url) ?: return null
        val project = (target as? ErgoDocumentationTarget)?.project ?: return null

        // Resolve the file off the EDT (may refresh the VFS), then navigate on it.
        ApplicationManager.getApplication().executeOnPooledThread {
            val file = LocalFileSystem.getInstance().refreshAndFindFileByPath(location.path)
            if (file != null && !project.isDisposed) {
                ApplicationManager.getApplication().invokeLater(
                    { OpenFileDescriptor(project, file, location.line, location.column).navigate(true) },
                    project.disposed,
                )
            }
        }
        // Navigating the editor is the whole effect; leave the popup unchanged.
        return null
    }
}
