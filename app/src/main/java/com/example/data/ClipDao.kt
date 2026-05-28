package com.example.data

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface ClipDao {
    @Query("SELECT * FROM clip_items ORDER BY timestamp DESC")
    fun getAllClips(): Flow<List<ClipItem>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertClip(clip: ClipItem)

    @Delete
    suspend fun deleteClip(clip: ClipItem)

    @Query("DELETE FROM clip_items")
    suspend fun clearAllClips()
}
