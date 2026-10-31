package com.spacedrift.fieldproof.security

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.util.UUID

/**
 * Outcome of importing a freshly captured file into the vault. Modelled
 * explicitly so callers can never mistake an encryption failure for a success
 * and silently persist the plaintext capture.
 */
sealed interface EvidenceImportResult {

    /** URI to persist for a usable import, or null when the import failed. */
    val storedUri: String?

    /** The plaintext capture was encrypted and removed; persist [storedUri]. */
    data class Sealed(override val storedUri: String) : EvidenceImportResult

    /** The input was already sealed; persist it unchanged. */
    data class AlreadySealed(override val storedUri: String) : EvidenceImportResult

    /** Nothing the vault manages (null/blank or a non-file URI); persist as-is. */
    data class Unmanaged(override val storedUri: String?) : EvidenceImportResult

    /**
     * The capture could not be secured (read/encrypt/delete failure). The
     * plaintext must NOT be persisted or uploaded; the caller should surface
     * the failure and let the user retry.
     */
    data class Failed(val reason: String) : EvidenceImportResult {
        override val storedUri: String? = null
    }
}

/**
 * Stores captured evidence on-device and reads it back for preview and upload.
 *
 * The production implementation encrypts every file with a device-bound Android
 * Keystore key before it touches disk. Implementations are responsible for
 * deleting files so a retention or inspection-delete action actually removes
 * sensitive bytes rather than orphaning them.
 */
interface EvidenceVault {

    /**
     * Reads a freshly captured plaintext file at [rawUri], encrypts it, removes
     * the plaintext and returns the sealed copy. Returns a typed
     * [EvidenceImportResult] so a failure is never mistaken for a usable URI.
     */
    suspend fun import(rawUri: String?): EvidenceImportResult

    /** Decrypts and returns the bytes for [localUri], or null if unreadable. */
    suspend fun read(localUri: String?): ByteArray?

    /** Deletes the managed file for [localUri]. Returns true if it existed. */
    suspend fun delete(localUri: String?): Boolean

    /** Deletes every managed evidence file. Returns the number removed. */
    suspend fun deleteAll(): Int

    /** Decodes [localUri] to a downsampled bitmap for display, or null. */
    fun decodeBitmap(localUri: String?, maxDimension: Int = DEFAULT_PREVIEW_DIMENSION): Bitmap?

    companion object {
        const val DEFAULT_PREVIEW_DIMENSION = 512
    }
}

/**
 * AES-GCM evidence vault backed by a private directory.
 *
 * Files written by older app versions (plain JPEGs) remain readable: [read] and
 * [decodeBitmap] fall back to returning the raw bytes when the envelope magic is
 * absent. New writes are always sealed.
 */
class EncryptedEvidenceVault(
    private val directory: File,
    private val cipher: EvidenceCipher,
    private val io: CoroutineDispatcher = Dispatchers.IO
) : EvidenceVault {

    override suspend fun import(rawUri: String?): EvidenceImportResult {
        if (rawUri.isNullOrBlank()) return EvidenceImportResult.Unmanaged(null)
        val raw = fileFromUri(rawUri) ?: return EvidenceImportResult.Unmanaged(rawUri)
        if (!raw.exists()) {
            return EvidenceImportResult.Failed("The captured file no longer exists.")
        }

        return try {
            val bytes = raw.readBytes()
            if (cipher.isSealed(bytes)) {
                return EvidenceImportResult.AlreadySealed(rawUri)
            }

            val sealed = withContext(io) {
                directory.mkdirs()
                val target = File(directory, "${UUID.randomUUID()}.fpenc")
                target.writeBytes(cipher.seal(bytes))
                target
            }

            // Only keep the sealed copy if the plaintext was actually removed;
            // otherwise we would leave an unencrypted capture on disk.
            if (!raw.delete()) {
                sealed.delete()
                return EvidenceImportResult.Failed(
                    "The plaintext capture could not be removed."
                )
            }

            EvidenceImportResult.Sealed(Uri.fromFile(sealed).toString())
        } catch (exception: Exception) {
            // Do not leave an unprotected capture behind on a failed import.
            raw.delete()
            EvidenceImportResult.Failed(
                exception.message ?: "The capture could not be secured."
            )
        }
    }

    override suspend fun read(localUri: String?): ByteArray? {
        val file = fileFromUri(localUri) ?: return null
        if (!file.exists()) return null

        return try {
            val bytes = withContext(io) { file.readBytes() }
            if (cipher.isSealed(bytes)) cipher.open(bytes) else bytes
        } catch (_: Exception) {
            null
        }
    }

    override suspend fun delete(localUri: String?): Boolean {
        val file = fileFromUri(localUri) ?: return false
        return withContext(io) { file.exists() && file.delete() }
    }

    override suspend fun deleteAll(): Int {
        return withContext(io) {
            val files = directory.listFiles() ?: return@withContext 0
            var removed = 0
            files.forEach { if (it.isFile && it.delete()) removed++ }
            removed
        }
    }

    override fun decodeBitmap(localUri: String?, maxDimension: Int): Bitmap? {
        val file = fileFromUri(localUri) ?: return null
        if (!file.exists()) return null

        return try {
            val bytes = file.readBytes()
            val plaintext = if (cipher.isSealed(bytes)) cipher.open(bytes) else bytes
            decodeSampled(plaintext, maxDimension)
        } catch (_: Exception) {
            null
        }
    }

    private fun decodeSampled(bytes: ByteArray, maxDimension: Int): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)

        var sampleSize = 1
        while (bounds.outWidth / sampleSize > maxDimension ||
            bounds.outHeight / sampleSize > maxDimension
        ) {
            sampleSize *= 2
        }

        val options = BitmapFactory.Options().apply { inSampleSize = sampleSize }
        return BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options)
    }

    private fun fileFromUri(localUri: String?): File? {
        if (localUri.isNullOrBlank()) return null
        return try {
            val uri = Uri.parse(localUri)
            if (uri.scheme != null && uri.scheme != "file") return null
            uri.path?.let(::File)
        } catch (_: Exception) {
            null
        }
    }
}

/**
 * No-op vault used by unit tests that construct repositories directly. Real
 * installations receive [EncryptedEvidenceVault] through Hilt.
 */
object PassthroughEvidenceVault : EvidenceVault {
    override suspend fun import(rawUri: String?): EvidenceImportResult =
        EvidenceImportResult.Unmanaged(rawUri)
    override suspend fun read(localUri: String?): ByteArray? = null
    override suspend fun delete(localUri: String?): Boolean = false
    override suspend fun deleteAll(): Int = 0
    override fun decodeBitmap(localUri: String?, maxDimension: Int): Bitmap? = null
}

/**
 * Process-wide access point for the vault so Compose screens can decrypt
 * evidence for preview without threading a dependency through every layer.
 * Hilt installs the production vault at startup; if a vault was never installed
 * (e.g. a UI-only test) a default keystore-backed vault is built lazily.
 */
object EvidenceVaults {

    @Volatile
    private var installed: EvidenceVault? = null

    fun install(vault: EvidenceVault) {
        installed = vault
    }

    fun instance(context: Context): EvidenceVault {
        installed?.let { return it }
        return synchronized(this) {
            installed ?: buildDefault(context).also { installed = it }
        }
    }

    fun reset() {
        installed = null
    }

    private fun buildDefault(context: Context): EvidenceVault {
        return EncryptedEvidenceVault(
            directory = File(context.filesDir, "evidence"),
            cipher = EvidenceCipher(AndroidKeystoreEvidenceKeyProvider())
        )
    }
}
