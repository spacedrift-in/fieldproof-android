package com.spacedrift.fieldproof.di

import android.content.ContentResolver
import android.content.Context
import androidx.room.Room
import com.spacedrift.fieldproof.data.local.FieldProofDatabase
import com.spacedrift.fieldproof.data.local.InspectionDao
import com.spacedrift.fieldproof.data.local.ChecklistItemDao
import com.spacedrift.fieldproof.data.local.EvidenceDao
import com.spacedrift.fieldproof.data.local.SyncOperationDao
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object AppModule {

    @Provides
    @Singleton
    fun provideContentResolver(
        @ApplicationContext context: Context
    ): ContentResolver {
        return context.contentResolver
    }

    @Provides
    @Singleton
    fun provideDatabase(
        @ApplicationContext context: Context
    ): FieldProofDatabase {
        return Room.databaseBuilder(
            context,
            FieldProofDatabase::class.java,
            "fieldproof.db"
        ).build()
    }

    @Provides
    fun provideInspectionDao(
        database: FieldProofDatabase
    ): InspectionDao {
        return database.inspectionDao()
    }

    @Provides
    fun provideChecklistItemDao(
        database: FieldProofDatabase
    ): ChecklistItemDao {
        return database.checklistItemDao()
    }

    @Provides
    fun provideEvidenceDao(
        database: FieldProofDatabase
    ): EvidenceDao {
        return database.evidenceDao()
    }

    @Provides
    fun provideSyncOperationDao(
        database: FieldProofDatabase
    ): SyncOperationDao {
        return database.syncOperationDao()
    }
}