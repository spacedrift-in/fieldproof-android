package com.spacedrift.fieldproof.di

import com.spacedrift.fieldproof.data.remote.ContentResolverEvidenceFileReader
import com.spacedrift.fieldproof.data.remote.EvidenceFileReader
import com.spacedrift.fieldproof.data.remote.EvidenceUploader
import com.spacedrift.fieldproof.data.repository.RemoteRepository
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
abstract class RepositoryModule {

    @Binds
    @Singleton
    abstract fun bindEvidenceUploader(
        remoteRepository: RemoteRepository
    ): EvidenceUploader

    @Binds
    @Singleton
    abstract fun bindEvidenceFileReader(
        reader: ContentResolverEvidenceFileReader
    ): EvidenceFileReader
}