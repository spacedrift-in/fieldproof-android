package com.spacedrift.fieldproof.data.local

import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

@Database(
    entities = [
        InspectionEntity::class,
        ChecklistItemEntity::class,
        EvidenceEntity::class,
        SyncOperationEntity::class
    ],
    version = 2,
    exportSchema = false
)
abstract class FieldProofDatabase : RoomDatabase() {

    abstract fun inspectionDao(): InspectionDao

    abstract fun checklistItemDao(): ChecklistItemDao

    abstract fun evidenceDao(): EvidenceDao

    abstract fun syncOperationDao(): SyncOperationDao

    companion object {
        /**
         * Adds the nullable `conflictDetail` column so a server conflict payload
         * can be preserved for the UI. Non-destructive: existing operation rows
         * are retained and the new column is simply NULL.
         */
        val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "ALTER TABLE sync_operations ADD COLUMN conflictDetail TEXT"
                )
            }
        }
    }
}
