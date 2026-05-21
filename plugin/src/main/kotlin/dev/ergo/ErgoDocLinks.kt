package dev.ergo

import java.net.URLDecoder
import java.net.URLEncoder
import java.nio.charset.StandardCharsets

/**
 * The link scheme tying a finding in the documentation popup to the source
 * location it originates from. [ErgoDocHtml] emits the hrefs; [ErgoDocLinkHandler]
 * consumes the clicks.
 */
object ErgoDocLinks {
    private const val SCHEME = "ergo://"

    /** A parsed source location, with [line]/[column] zero-based for the editor. */
    data class Location(val path: String, val line: Int, val column: Int)

    /**
     * The href for a finding originating at [pos] (`file:line:col`, one-based),
     * or null when [pos] is absent or not a usable location.
     */
    fun href(pos: String?): String? {
        if (pos.isNullOrBlank() || parsePos(pos) == null) return null
        return SCHEME + URLEncoder.encode(pos, StandardCharsets.UTF_8)
    }

    /** Parses an `ergo://` [url] produced by [href] back into a [Location]. */
    fun parse(url: String): Location? {
        if (!url.startsWith(SCHEME)) return null
        return parsePos(URLDecoder.decode(url.removePrefix(SCHEME), StandardCharsets.UTF_8))
    }

    /**
     * Parses `file:line:col` into a zero-based [Location]. The file path itself
     * may contain colons (e.g. a Windows drive letter), so line and column are
     * taken from the right.
     */
    private fun parsePos(pos: String): Location? {
        val column = pos.substringAfterLast(':', "").toIntOrNull() ?: return null
        val rest = pos.substringBeforeLast(':')
        val line = rest.substringAfterLast(':', "").toIntOrNull() ?: return null
        val path = rest.substringBeforeLast(':').takeIf { it.isNotEmpty() } ?: return null
        return Location(
            path = path.replace('\\', '/'),
            line = (line - 1).coerceAtLeast(0),
            column = (column - 1).coerceAtLeast(0),
        )
    }
}
