package com.example.data

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "clip_items")
data class ClipItem(
    @PrimaryKey(autoGenerate = true) val id: Int = 0,
    val text: String,
    val timestamp: Long = System.currentTimeMillis(),
    val isSent: Boolean, // true if sent from phone -> PC, false if received from PC -> phone
    val peerName: String
)
