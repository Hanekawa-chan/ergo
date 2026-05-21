package dev.ergo

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

/** Unit tests for encoding and parsing source-location links. */
class ErgoDocLinksTest {

    @Test
    fun roundTripsAUnixPosition() {
        val href = ErgoDocLinks.href("/home/u/search.go:33:6")
        assertNotNull("a well-formed position should yield a link", href)

        val location = ErgoDocLinks.parse(href!!)!!
        assertEquals("/home/u/search.go", location.path)
        assertEquals(32, location.line) // zero-based for the editor
        assertEquals(5, location.column)
    }

    @Test
    fun handlesAWindowsDriveColon() {
        val location = ErgoDocLinks.parse(ErgoDocLinks.href("""C:\go\a.go:10:2""")!!)!!
        assertEquals("C:/go/a.go", location.path)
        assertEquals(9, location.line)
        assertEquals(1, location.column)
    }

    @Test
    fun rejectsBlankOrMalformedPositions() {
        assertNull(ErgoDocLinks.href(null))
        assertNull(ErgoDocLinks.href(""))
        assertNull(ErgoDocLinks.href("/no/line/or/column.go"))
    }

    @Test
    fun parseIgnoresForeignSchemes() {
        assertNull(ErgoDocLinks.parse("https://example.com/x:1:1"))
    }
}
