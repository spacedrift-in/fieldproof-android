package com.spacedrift.fieldproof.security

import android.net.Uri
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import javax.crypto.KeyGenerator

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class EncryptedEvidenceVaultTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    private val key = KeyGenerator.getInstance("AES").apply { init(256) }.generateKey()

    private lateinit var directory: File
    private lateinit var vault: EncryptedEvidenceVault

    private fun newVault(): EncryptedEvidenceVault {
        directory = tempFolder.newFolder("evidence")
        vault = EncryptedEvidenceVault(
            directory = directory,
            cipher = EvidenceCipher(EvidenceKeyProvider { key })
        )
        return vault
    }

    @Test
    fun `store writes an encrypted copy and read returns the original`() = runBlocking {
        newVault()
        val plaintext = "evidence bytes".toByteArray()

        val result = vault.import(
            writeRawFile("FIELDPROOF_1.jpg", plaintext).let(Uri::fromFile).toString()
        )

        assertTrue(result is EvidenceImportResult.Sealed)
        val uri = result.storedUri!!
        val stored = File(Uri.parse(uri).path!!)
        assertTrue(stored.exists())
        assertTrue(stored.name.endsWith(".fpenc"))
        assertFalse(
            "on-disk bytes must be encrypted, not the plaintext",
            stored.readBytes().contentEquals(plaintext)
        )
        assertArrayEquals(plaintext, vault.read(uri))
    }

    @Test
    fun `import removes the plaintext source file`() = runBlocking {
        newVault()
        val raw = writeRawFile("FIELDPROOF_2.jpg", "secret".toByteArray())

        vault.import(Uri.fromFile(raw).toString())

        assertFalse("plaintext capture must be deleted after import", raw.exists())
    }

    @Test
    fun `import ignores an already sealed file`() = runBlocking {
        newVault()
        val sealed = EvidenceCipher(EvidenceKeyProvider { key }).seal("x".toByteArray())
        val raw = writeRawFile("already.fpenc", sealed)

        val result = vault.import(Uri.fromFile(raw).toString())

        assertTrue(result is EvidenceImportResult.AlreadySealed)
    }

    @Test
    fun `read falls back to plaintext for legacy files`() = runBlocking {
        newVault()
        val plaintext = "legacy jpeg".toByteArray()
        val legacy = File(directory, "legacy.jpg").apply { writeBytes(plaintext) }

        assertArrayEquals(plaintext, vault.read(Uri.fromFile(legacy).toString()))
    }

    @Test
    fun `read returns null for unmanaged or missing files`() = runBlocking {
        newVault()
        assertNull(vault.read(null))
        assertNull(vault.read("content://media/external/images/1"))
        assertNull(vault.read(Uri.fromFile(File(directory, "missing.fpenc")).toString()))
    }

    @Test
    fun `delete removes the file and reports whether it existed`() = runBlocking {
        newVault()
        val uri = vault.import(
            writeRawFile("FIELDPROOF_3.jpg", "bytes".toByteArray()).let(Uri::fromFile).toString()
        )!!.storedUri!!

        assertTrue(vault.delete(uri))
        assertFalse(File(Uri.parse(uri).path!!).exists())
        assertFalse(vault.delete(uri))
    }

    @Test
    fun `deleteAll clears every managed file`() = runBlocking {
        newVault()
        repeat(3) { index ->
            vault.import(
                writeRawFile("FIELDPROOF_$index.jpg", byteArrayOf(index.toByte()))
                    .let(Uri::fromFile)
                    .toString()
            )
    @Test
    fun `import reports failure and removes the plaintext when sealing fails`() = runBlocking {
        directory = tempFolder.newFolder("evidence-fail")
        val failingVault = EncryptedEvidenceVault(
            directory = directory,
            cipher = EvidenceCipher(EvidenceKeyProvider { throw IllegalStateException("keystore unavailable") })
        )
        val raw = writeRawFile("FIELDPROOF_fail.jpg", "secret".toByteArray())

        val result = failingVault.import(Uri.fromFile(raw).toString())

        assertTrue(result is EvidenceImportResult.Failed)
        assertFalse("plaintext capture must not be left behind", raw.exists())
    }
}

        assertEquals(3, vault.deleteAll())
        assertEquals(0, directory.listFiles()?.size ?: 0)
    }

    @Test
    fun `distinct captures produce distinct stored names`() = runBlocking {
        newVault()
        val a = vault.import(writeRawFile("a.jpg", byteArrayOf(1)).let(Uri::fromFile).toString())!!.storedUri
        val b = vault.import(writeRawFile("b.jpg", byteArrayOf(2)).let(Uri::fromFile).toString())!!.storedUri

        assertNotEquals(a, b)
    }

    private fun writeRawFile(name: String, bytes: ByteArray): File {
        val folder = tempFolder.newFolder("raw-${File(name).nameWithoutExtension}-${counter++}")
        return File(folder, name).apply { writeBytes(bytes) }
    }

    private var counter = 0
}
