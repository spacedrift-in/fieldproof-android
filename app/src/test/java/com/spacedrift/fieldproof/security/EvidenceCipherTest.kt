package com.spacedrift.fieldproof.security

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import javax.crypto.KeyGenerator

class EvidenceCipherTest {

    private val key = KeyGenerator.getInstance("AES").apply { init(256) }.generateKey()

    private fun cipher(provider: EvidenceKeyProvider = EvidenceKeyProvider { key }) =
        EvidenceCipher(provider)

    @Test
    fun `seal then open returns the original bytes`() {
        val plaintext = "captured evidence".toByteArray()

        val sealed = cipher().seal(plaintext)

        assertArrayEquals(plaintext, cipher().open(sealed))
    }

    @Test
    fun `sealed output does not contain the plaintext and is marked sealed`() {
        val plaintext = "top secret observations".toByteArray()

        val sealed = cipher().seal(plaintext)

        assertTrue(cipher().isSealed(sealed))
        assertFalse(String(sealed, Charsets.ISO_8859_1).contains("top secret"))
    }

    @Test
    fun `each seal uses a fresh iv so ciphertext differs`() {
        val plaintext = "same input".toByteArray()

        val first = cipher().seal(plaintext)
        val second = cipher().seal(plaintext)

        assertNotEquals(
            first.toList(),
            second.toList()
        )
        assertArrayEquals(plaintext, cipher().open(first))
        assertArrayEquals(plaintext, cipher().open(second))
    }

    @Test
    fun `plaintext is not detected as sealed`() {
        assertFalse(cipher().isSealed("just a jpeg".toByteArray()))
        assertFalse(cipher().isSealed(byteArrayOf()))
    }

    @Test(expected = IllegalArgumentException::class)
    fun `opening non-envelope bytes fails`() {
        cipher().open("not sealed".toByteArray())
    }

    @Test(expected = Exception::class)
    fun `tampering with the ciphertext fails authentication`() {
        val sealed = cipher().seal("authentic".toByteArray())
        sealed[sealed.size - 1] = (sealed[sealed.size - 1].toInt() xor 0x01).toByte()

        cipher().open(sealed)
    }
}
