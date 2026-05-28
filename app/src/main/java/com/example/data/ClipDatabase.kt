package com.example.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

@Database(entities = [ClipItem::class], version = 1, exportSchema = false)
abstract class ClipDatabase : RoomDatabase() {
    abstract val clipDao: ClipDao

    companion object {
        @Volatile
        private var INSTANCE: ClipDatabase? = null

        fun getDatabase(context: Context): ClipDatabase {
            return INSTANCE ?: synchronized(this) {
                val instance = Room.databaseBuilder(
                    context.applicationContext,
                    ClipDatabase::class.java,
                    "clip_sync_database"
                ).build()
                INSTANCE = instance
                instance
            }
        }
    }
}
