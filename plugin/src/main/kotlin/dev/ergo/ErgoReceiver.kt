package dev.ergo

/**
 * Matches the receiver of a hovered Go method against the `recv` field of an
 * `ergo` analysis result, so a method's documentation shows only its own
 * errors.
 *
 * `ergo errors` is queried by bare function name; for a method it therefore
 * returns every same-named method in the package, each tagged with its
 * receiver type. This narrows that set back down to the hovered method.
 */
object ErgoReceiver {

    /**
     * Reports whether [cliRecv] — the `recv` of an [ErgoFunction] — and
     * [hoveredRecv] — the receiver-type text of the hovered PSI method, or null
     * for a plain function — denote the same receiver.
     *
     * The two are produced by different tools and differ in form: `ergo`
     * qualifies the type with its package name and keeps the pointer star
     * (`*rcv.Reader`), while the PSI text is package-local (`*Reader`). Both are
     * reduced to a bare type name — pointer star, package qualifier and type
     * arguments removed — before comparison. Go forbids declaring one method
     * name twice on a type, so the bare name pins the receiver uniquely.
     */
    fun matches(cliRecv: String?, hoveredRecv: String?): Boolean =
        bareTypeName(cliRecv) == bareTypeName(hoveredRecv)

    /** Reduces a receiver type to its bare name: no `*`, package, or `[...]`. */
    private fun bareTypeName(recv: String?): String {
        var s = recv.orEmpty().trim().removePrefix("*").trim()
        val bracket = s.indexOf('[')
        if (bracket >= 0) s = s.take(bracket)
        val dot = s.lastIndexOf('.')
        if (dot >= 0) s = s.substring(dot + 1)
        return s.trim()
    }
}
