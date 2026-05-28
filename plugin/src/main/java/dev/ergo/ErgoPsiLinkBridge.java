package dev.ergo;

import com.intellij.lang.documentation.psi.PsiDocumentationLinkHandler;
import com.intellij.lang.documentation.psi.PsiElementDocumentationTarget;
import com.intellij.openapi.project.Project;
import com.intellij.platform.backend.documentation.LinkResolveResult;
import com.intellij.psi.PsiElement;
import org.jetbrains.annotations.Nullable;

/**
 * Bridges to the platform's {@link PsiDocumentationLinkHandler}, which is the
 * only handler that knows how to follow {@code psi_element://} and
 * external-doc links — but it only accepts its own {@link
 * PsiElementDocumentationTarget} type, and both that class and the handler
 * are Kotlin {@code internal}, so a Kotlin caller cannot construct or invoke
 * them directly. Java is not subject to Kotlin's visibility check, so this
 * thin Java helper lets {@link ErgoDocLinkHandler} reuse the standard
 * resolution for the wrapped Go-plugin documentation's links.
 */
final class ErgoPsiLinkBridge {

    private static final PsiDocumentationLinkHandler HANDLER = new PsiDocumentationLinkHandler();

    private ErgoPsiLinkBridge() {}

    static @Nullable LinkResolveResult resolveAsPsi(Project project, PsiElement context, String url) {
        return HANDLER.resolveLink(new PsiElementDocumentationTarget(project, context), url);
    }
}