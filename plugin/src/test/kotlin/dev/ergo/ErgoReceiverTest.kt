package dev.ergo

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Unit tests for matching a hovered method's receiver to an `ergo` result. */
class ErgoReceiverTest {

    @Test
    fun matchesAcrossPackageQualifierAndPointer() {
        // ergo qualifies the type and keeps the star; the PSI text is local.
        assertTrue(ErgoReceiver.matches("*rcv.Reader", "*Reader"))
        assertTrue(ErgoReceiver.matches("rcv.Reader", "Reader"))
    }

    @Test
    fun matchesRegardlessOfPointerOrValueReceiver() {
        assertTrue(ErgoReceiver.matches("*rcv.Reader", "Reader"))
        assertTrue(ErgoReceiver.matches("rcv.Reader", "*Reader"))
    }

    @Test
    fun matchesGenericReceiverIgnoringTypeArguments() {
        assertTrue(ErgoReceiver.matches("*rcv.List[T]", "*List[T]"))
        assertTrue(ErgoReceiver.matches("rcv.Map[K,V]", "Map[K, V]"))
    }

    @Test
    fun distinguishesDifferentReceivers() {
        assertFalse(ErgoReceiver.matches("*rcv.Reader", "*Writer"))
    }

    @Test
    fun plainFunctionMatchesOnlyAnAbsentReceiver() {
        assertTrue(ErgoReceiver.matches(null, null))
        assertTrue(ErgoReceiver.matches("", null))
        assertFalse(ErgoReceiver.matches("*rcv.Reader", null))
        assertFalse(ErgoReceiver.matches(null, "*Reader"))
    }
}
