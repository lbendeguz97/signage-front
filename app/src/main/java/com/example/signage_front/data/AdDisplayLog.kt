package com.example.signage_front.data

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "ad_display_log")
data class AdDisplayLog(
    @PrimaryKey(autoGenerate = true) val id: Long = 0L,
    val adId: String,
    val timestamp: Long,
    val durationMs: Long,
    val clicked: Boolean,
    val exitedScreen: Boolean,
    val audienceAge: String? = null,
    val audienceGender: String? = null,
    val syncStatus: String = "PENDING" // PENDING, SYNCED
)
