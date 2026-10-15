package com.spacedrift.fieldproof.di

import com.spacedrift.fieldproof.sync.SyncScheduler
import com.spacedrift.fieldproof.sync.WorkManagerSyncScheduler
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
abstract class SyncModule {

    @Binds
    @Singleton
    abstract fun bindSyncScheduler(
        scheduler: WorkManagerSyncScheduler
    ): SyncScheduler
}
