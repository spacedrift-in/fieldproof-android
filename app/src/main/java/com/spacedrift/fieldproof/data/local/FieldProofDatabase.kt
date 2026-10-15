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
    version = 4,
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

        /**
         * Adds the checklist-outcome columns (outcome/note/syncedVersion/syncState)
         * so PASS/FAIL/NOT_APPLICABLE and per-item notes can be tracked and synced.
         * Non-destructive: existing rows keep their `completed` flag and start as
         * NOT_STARTED/LOCAL.
         */
        val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "ALTER TABLE checklist_items ADD COLUMN outcome TEXT NOT NULL " +
                        "DEFAULT 'NOT_STARTED'"
                )
                db.execSQL(
                    "ALTER TABLE checklist_items ADD COLUMN note TEXT"
                )
                db.execSQL(
                    "ALTER TABLE checklist_items ADD COLUMN syncedVersion INTEGER"
                )
                db.execSQL(
                    "ALTER TABLE checklist_items ADD COLUMN syncState TEXT NOT NULL " +
                        "DEFAULT 'LOCAL'"
                )
            }
        }

        /**
         * Adds the compare-and-set revision columns that make checklist-outcome
         * sync safe under an interrupted/resumed race. `checklist_items.localRevision`
         * counts local edits; `sync_operations.revision` records which edit an
         * operation uploads. A successful upload only acknowledges the item/operation
         * whose revision still matches, so a slow upload cannot overwrite a newer
         * local edit. Non-destructive: existing rows start at revision 0.
         */
        val MIGRATION_3_4 = object : Migration(3, 4) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "ALTER TABLE checklist_items ADD COLUMN localRevision INTEGER " +
                        "NOT NULL DEFAULT 0"
                )
                db.execSQL(
                    "ALTER TABLE sync_operations ADD COLUMN revision INTEGER " +
                        "NOT NULL DEFAULT 0"
                )
            }
        }
    }
}
