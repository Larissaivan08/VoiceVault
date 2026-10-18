package com.example.voicevault.db

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.sqlite.db.SupportSQLiteDatabase

@Database(entities = [Folder::class, Recording::class], version = 2, exportSchema = false)
abstract class AppDb : RoomDatabase() {
    abstract fun folderDao(): FolderDao
    abstract fun recordingDao(): RecordingDao

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
                    .build().also { instance = it }
            }
    }
}
