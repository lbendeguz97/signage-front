package com.example.signage_front.data

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "ad_status")
data class AdStatus(
    @PrimaryKey val adId: String,
    val adAllowed: Boolean,
    val adult: Boolean,
    val path: String,
    val url: String? = null,
    val display: String,
    val displayTime: Int?,
    val mediaType: String? = null,
    val expectedChecksum: String? = null,
    val expectedSize: Long = 0L,
    val syncStatus: String = "PENDING", // PENDING, DOWNLOADING, VERIFIED, ERROR
    val lastUpdated: Long = System.currentTimeMillis()
)
