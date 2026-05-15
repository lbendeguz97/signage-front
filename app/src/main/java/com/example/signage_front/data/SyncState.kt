package com.example.signage_front.data

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "sync_state")
data class SyncState(
    @PrimaryKey val tableName: String,
    val timestamp: String,
    val lastSyncLocal: Long = System.currentTimeMillis()
)
