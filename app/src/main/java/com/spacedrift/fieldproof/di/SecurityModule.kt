package com.spacedrift.fieldproof.di

import android.content.Context
import com.spacedrift.fieldproof.security.AndroidKeystoreEvidenceKeyProvider
import com.spacedrift.fieldproof.security.EncryptedEvidenceVault
import com.spacedrift.fieldproof.security.EvidenceCipher
import com.spacedrift.fieldproof.security.EvidenceVault
import com.spacedrift.fieldproof.security.EvidenceVaults
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import java.io.File
import javax.inject.Singleton

/**
 * Wires the device-bound evidence vault. The same singleton is installed into
 * [EvidenceVaults] so Compose previews can decrypt files without a direct Hilt
 * dependency in the UI layer.
 */
@Module
@InstallIn(SingletonComponent::class)
object SecurityModule {

    @Provides
    @Singleton
    fun provideEvidenceVault(
        @ApplicationContext context: Context
    ): EvidenceVault {
        val vault = EncryptedEvidenceVault(
            directory = File(context.filesDir, "evidence"),
            cipher = EvidenceCipher(AndroidKeystoreEvidenceKeyProvider())
        )
        EvidenceVaults.install(vault)
        return vault
    }
}
