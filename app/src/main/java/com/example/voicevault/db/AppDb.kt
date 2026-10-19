package com.example.voicevault.db

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.sqlite.db.SupportSQLiteDatabase

@Database(entities = [Folder::class, Recording::class, PendingRecord::class], version = 3, exportSchema = false)
abstract class AppDb : RoomDatabase() {
    abstract fun folderDao(): FolderDao
    abstract fun recordingDao(): RecordingDao
    abstract fun pendingDao(): PendingDao

    companion object {
        @Volatile private var instance: AppDb? = null

        fun get(context: Context): AppDb =
            instance ?: synchronized(this) {
                instance ?: Room.databaseBuilder(
                    context.applicationContext, AppDb::class.java, "voicevault.db"
                )
                    // Migração v1 -> v2: adiciona a coluna "format" sem perder dados.
                    .addMigrations(object : androidx.room.migration.Migration(1, 2) {
                        override fun migrate(db: SupportSQLiteDatabase) {
                            db.execSQL(
                                "ALTER TABLE recordings ADD COLUMN format TEXT NOT NULL DEFAULT 'M4A'"
                            )
                        }
                    })
                    // Migração v2 -> v3: tabela de sessões pendentes (salvamento à prova de crash).
                    .addMigrations(object : androidx.room.migration.Migration(2, 3) {
                        override fun migrate(db: SupportSQLiteDatabase) {
                            db.execSQL(
                                "CREATE TABLE IF NOT EXISTS `pending` (" +
                                "`id` INTEGER NOT NULL, `outPath` TEXT NOT NULL, `format` TEXT NOT NULL, " +
                                "`folderId` INTEGER, `startedAt` INTEGER NOT NULL, " +
                                "`pausedTotalMs` INTEGER NOT NULL, `paused` INTEGER NOT NULL, " +
                                "PRIMARY KEY(`id`))"
                            )
                        }
                    })
                    .build().also { instance = it }
            }
    }
}
