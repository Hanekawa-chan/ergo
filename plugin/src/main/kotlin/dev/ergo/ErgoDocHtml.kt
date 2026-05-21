package dev.ergo

import com.intellij.openapi.util.text.StringUtil

/**
 * Renders an [ErgoResult] as the HTML "Errors (ergo)" section appended to a Go
 * function's Quick Documentation popup.
 *
 * Pure string formatting — no platform state — so it is unit-testable. The
 * markup reuses the platform's `sections` and `grayed` documentation styles.
 */
object ErgoDocHtml {

    /** Builds the section for [result]. */
    fun section(result: ErgoResult): String {
        val content = StringBuilder()
        when (result) {
            is ErgoResult.Failure -> content.append(grayed(escape(result.message)))
            is ErgoResult.Success -> renderFunctions(result.functions, content)
        }
        return "<table class='sections'>" +
            "<tr><td valign='top' class='section'><p>Errors (ergo)</p></td>" +
            "<td valign='top'>$content</td></tr></table>"
    }

    private fun renderFunctions(functions: List<ErgoFunction>, out: StringBuilder) {
        val withErrors = functions.filterNot { it.findings.isNullOrEmpty() }
        if (withErrors.isEmpty()) {
            out.append(grayed("returns no error"))
            return
        }
        // More than one match means same-named methods/functions; label each.
        val labelEach = withErrors.size > 1
        for (fn in withErrors) {
            if (labelEach) {
                out.append("<p><code>").append(escape(qualifiedName(fn))).append("</code></p>")
            }
            for (finding in fn.findings.orEmpty()) {
                out.append(renderFinding(finding))
            }
        }
    }

    private fun renderFinding(f: ErgoFinding): String {
        val detail = when (f.kind) {
            "sentinel" -> code(f.name)
            "type" -> code(f.type)
            "constructed" -> {
                val message = if (f.message.isNullOrEmpty()) {
                    grayed("non-constant message")
                } else {
                    "“" + escape(f.message) + "”"
                }
                if (f.wrapped) "$message ${grayed("wraps another error")}" else message
            }
            else -> grayed(escape(f.reason?.ifEmpty { null } ?: "unresolved"))
        }
        // Link the finding to its origin when a source position is known.
        val href = ErgoDocLinks.href(f.pos)
        val body = if (href == null) detail else "<a href=\"$href\">$detail</a>"
        return "<p>$body&nbsp;&nbsp;${grayed(escape(f.kind))}</p>"
    }

    private fun qualifiedName(fn: ErgoFunction): String =
        if (fn.recv.isNullOrEmpty()) fn.name else "(${fn.recv}).${fn.name}"

    private fun code(text: String?): String = "<code>" + escape(text.orEmpty()) + "</code>"

    private fun grayed(text: String): String = "<span class='grayed'>$text</span>"

    private fun escape(text: String): String = StringUtil.escapeXmlEntities(text)
}
