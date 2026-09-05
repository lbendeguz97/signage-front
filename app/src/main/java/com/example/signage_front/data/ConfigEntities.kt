package com.example.signage_front.data

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "group_config")
data class GroupConfig(
    @PrimaryKey val groupId: Long,
    val name: String,
    val configJson: String, // Stored as a raw JSON string
    val playlistId: Long?,
    val scheduleId: Long?,
    val sspConnectivityId: Long?
)

@Entity(tableName = "tablet_metadata")
data class TabletMetadata(
    @PrimaryKey val androidId: String,
    val refId: String?
)

@Entity(tableName = "playlists")
data class Playlist(
    @PrimaryKey val playlistId: Long,
    val name: String
)

@Entity(tableName = "playlist_ads", primaryKeys = ["playlistId", "position"])
data class PlaylistAd(
    val playlistId: Long,
    val adId: Long,
    val position: Int,
    val duration: Int
)

@Entity(tableName = "campaigns")
data class Campaign(
    @PrimaryKey val campaignId: Long,
    val name: String,
    val startDate: String,
    val endDate: String
)

@Entity(tableName = "campaign_schedules", primaryKeys = ["campaignId", "date"])
data class CampaignSchedule(
    val campaignId: Long,
    val scheduleId: Long,
    val date: String
)

@Entity(tableName = "schedules")
data class Schedule(
    @PrimaryKey val scheduleId: Long,
    val name: String,
    val campaignId: Long? // Nullable for default group schedules
)

@Entity(tableName = "schedule_intervals")
data class ScheduleInterval(
    @PrimaryKey val intervalId: Long,
    val scheduleId: Long,
    val playlistId: Long,
    val startTime: String,
    val endTime: String
)

@Entity(tableName = "schedule_rules")
data class ScheduleRule(
    @PrimaryKey val ruleId: Long,
    val scheduleId: Long,
    val templateId: Long?,
    val ruleType: String,
    val dayOfWeek: Int?,
    val specificDate: String?
)

@Entity(tableName = "campaign_triggers", primaryKeys = ["triggerId", "date"])
data class CampaignTrigger(
    val triggerId: Long,
    val name: String,
    val type: String,
    val conditionConfig: String, // Stored as a raw JSON string
    val actionType: String,
    val actionId: Long,
    val priority: String,
    val cooldownMinutes: Int,
    val enabled: Boolean,
    val campaignId: Long,
    val date: String
)

@Entity(tableName = "ssp_connectivities")
data class SspConnectivity(
    @PrimaryKey val id: Long,
    val name: String,
    val provider: String,
    val endpointUrl: String,
    val refId: String?,
    val dealId: String?,
    val lineItemId: String?,
    val additionalParams: String, // Stored as a raw JSON string
    val apiKey: String? // LMX API key, sent as the "Token" request header
)

@Entity(tableName = "cached_ssp_ads")
data class CachedSspAd(
    @PrimaryKey val mediaUrl: String,
    val localPath: String,
    val mediaType: String,
    val sizeBytes: Long,
    val expiresAt: Long,
    val lastAccessed: Long,
    val durationSeconds: Int = 0
)

@Entity(tableName = "ssp_slot_log")
data class SspSlotLog(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val timestamp: Long,
    val budgetMs: Long,
    val filledMs: Long,
    val overrunMs: Long,
    val adsPlayed: Int,
    val fallbackUsed: Boolean
)

@Entity(tableName = "pending_beacons")
data class PendingBeacon(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val url: String,
    val createdAt: Long = System.currentTimeMillis(),
    val retryCount: Int = 0
)
