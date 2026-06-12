package com.lmusic.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

@Database(entities = [DownloadRecord::class], version = 4, exportSchema = false)
abstract class DownloadDatabase : RoomDatabase() {
    abstract fun downloadDao(): DownloadDao

    companion object {
        @Volatile
        private var INSTANCE: DownloadDatabase? = null

        /**
         * v3 → v4: adds categorization + weekly-mix columns.  A real migration
         * (not destructive) so the user's existing download history survives the
         * update.
         */
        private val MIGRATION_3_4 = object : Migration(3, 4) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE downloads ADD COLUMN category TEXT")
                db.execSQL("ALTER TABLE downloads ADD COLUMN isAutoDownload INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE downloads ADD COLUMN expiresAt INTEGER")
            }
        }

        fun get(ctx: Context): DownloadDatabase =
            INSTANCE ?: synchronized(this) {
                INSTANCE ?: Room.databaseBuilder(
                    ctx.applicationContext,
                    DownloadDatabase::class.java,
                    "lmusic.db"
                )
                    .addMigrations(MIGRATION_3_4)
                    .fallbackToDestructiveMigration()  // safety net for older/unknown versions
                    .build()
                    .also { INSTANCE = it }
            }
    }
}
