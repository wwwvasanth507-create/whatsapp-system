package com.campuschat.app.core.crypto

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SecureRandomProviderTest {

    private val provider = SecureRandomProvider()

    @Test
    fun testRandomBytesGeneration() {
        val bytes16 = provider.nextBytes(16)
        val bytes32 = provider.nextBytes(32)

        assertEquals(16, bytes16.size)
        assertEquals(32, bytes32.size)

        // Ensure non-identical byte sequences
        assertNotEquals(bytes16.toList(), bytes32.take(16).toList())
    }

    @Test
    fun testRandomPositiveIntegerId() {
        val id1 = provider.nextIntId()
        val id2 = provider.nextIntId()

        assertTrue("Random integer ID must be non-negative", id1 >= 0)
        assertTrue("Random integer ID must be non-negative", id2 >= 0)
    }

    @Test(expected = IllegalArgumentException::class)
    fun testInvalidByteSizeThrowsException() {
        provider.nextBytes(0)
    }
}
