package com.example.voicevault.db

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

@Database(entities = [Folder::class, Recording::class], version = 1, exportSchema = false)
abstract class AppDb : RoomDatabase() {
    abstract fun folderDao(): FolderDao
    abstract fun recordingDao(): RecordingDao

    companion object {
        @Volatile private var instance: AppDb? = null

        fun get(context: Context): AppDb =
            instance ?: synchronized(this) {
                instance ?: Room.databaseBuilder(
                    context.applicationContext, AppDb::class.java, "voicevault.db"
                ).build().also { instance = it }
            }
    }
}
