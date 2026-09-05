package com.example.signage_front.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import kotlinx.coroutines.flow.Flow

@Dao
interface ConfigDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertGroupConfig(groupConfig: GroupConfig)

    @Query("SELECT * FROM group_config LIMIT 1")
    suspend fun getGroupConfig(): GroupConfig?

    @Query("SELECT * FROM group_config LIMIT 1")
    fun getGroupConfigFlow(): Flow<GroupConfig?>

    @Query("SELECT * FROM playlist_ads WHERE playlistId = :playlistId ORDER BY position ASC")
    suspend fun getPlaylistAds(playlistId: Long): List<PlaylistAd>

    @Query("SELECT * FROM playlist_ads WHERE playlistId = :playlistId ORDER BY position ASC")
    fun getPlaylistAdsFlow(playlistId: Long): Flow<List<PlaylistAd>>

    @Query("SELECT * FROM campaigns")
    suspend fun getAllCampaigns(): List<Campaign>

    @Query("SELECT * FROM campaign_schedules WHERE campaignId = :campaignId AND date = :date LIMIT 1")
    suspend fun getCampaignScheduleForDate(campaignId: Long, date: String): CampaignSchedule?

    @Query("SELECT * FROM schedule_intervals WHERE scheduleId = :scheduleId AND startTime <= :time AND endTime > :time LIMIT 1")
    suspend fun getIntervalForTime(scheduleId: Long, time: String): ScheduleInterval?

    @Query("SELECT * FROM campaign_triggers WHERE campaignId = :campaignId AND date = :date")
    suspend fun getCampaignTriggersByDate(campaignId: Long, date: String): List<CampaignTrigger>

    @Query("SELECT * FROM tablet_metadata WHERE androidId = :androidId LIMIT 1")
    suspend fun getTabletMetadata(androidId: String): TabletMetadata?

    @Query("SELECT * FROM ssp_connectivities WHERE id = :id LIMIT 1")
    suspend fun getSspConnectivity(id: Long): SspConnectivity?

    @Query("SELECT * FROM ssp_connectivities")
    suspend fun getAllSspConnectivities(): List<SspConnectivity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertTabletMetadata(tabletMetadata: TabletMetadata)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertPlaylists(playlists: List<Playlist>)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertPlaylistAds(playlistAds: List<PlaylistAd>)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertCampaigns(campaigns: List<Campaign>)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertCampaignSchedules(campaignSchedules: List<CampaignSchedule>)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertSchedules(schedules: List<Schedule>)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertScheduleIntervals(intervals: List<ScheduleInterval>)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertScheduleRules(rules: List<ScheduleRule>)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertCampaignTriggers(triggers: List<CampaignTrigger>)

    @Query("DELETE FROM group_config")
    suspend fun clearGroupConfig()

    @Query("DELETE FROM tablet_metadata")
    suspend fun clearTabletMetadata()

    @Query("DELETE FROM playlists")
    suspend fun clearPlaylists()

    @Query("DELETE FROM playlist_ads")
    suspend fun clearPlaylistAds()

    @Query("DELETE FROM campaigns")
    suspend fun clearCampaigns()

    @Query("DELETE FROM campaign_schedules")
    suspend fun clearCampaignSchedules()

    @Query("DELETE FROM schedules")
    suspend fun clearSchedules()

    @Query("DELETE FROM schedule_intervals")
    suspend fun clearScheduleIntervals()

    @Query("DELETE FROM schedule_rules")
    suspend fun clearScheduleRules()

    @Query("DELETE FROM campaign_triggers")
    suspend fun clearCampaignTriggers()

    @Transaction
    suspend fun syncConfiguration(
        groupConfig: GroupConfig,
        tabletMetadata: TabletMetadata,
        playlists: List<Playlist>,
        playlistAds: List<PlaylistAd>,
        campaigns: List<Campaign>,
        campaignSchedules: List<CampaignSchedule>,
        schedules: List<Schedule>,
        intervals: List<ScheduleInterval>,
        rules: List<ScheduleRule>,
        triggers: List<CampaignTrigger>
    ) {
        clearGroupConfig()
        clearTabletMetadata()
        clearPlaylists()
        clearPlaylistAds()
        clearCampaigns()
        clearCampaignSchedules()
        clearSchedules()
        clearScheduleIntervals()
        clearScheduleRules()
        clearCampaignTriggers()

        insertGroupConfig(groupConfig)
        insertTabletMetadata(tabletMetadata)
        if (playlists.isNotEmpty()) insertPlaylists(playlists)
        if (playlistAds.isNotEmpty()) insertPlaylistAds(playlistAds)
        if (campaigns.isNotEmpty()) insertCampaigns(campaigns)
        if (campaignSchedules.isNotEmpty()) insertCampaignSchedules(campaignSchedules)
        if (schedules.isNotEmpty()) insertSchedules(schedules)
        if (intervals.isNotEmpty()) insertScheduleIntervals(intervals)
        if (rules.isNotEmpty()) insertScheduleRules(rules)
        if (triggers.isNotEmpty()) insertCampaignTriggers(triggers)
    }

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertSspConnectivities(sspList: List<SspConnectivity>)

    @Query("DELETE FROM ssp_connectivities")
    suspend fun clearSspConnectivities()

    @Transaction
    suspend fun syncSspConnectivities(sspList: List<SspConnectivity>) {
        clearSspConnectivities()
        if (sspList.isNotEmpty()) {
            insertSspConnectivities(sspList)
        }
    }

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertCachedSspAd(ad: CachedSspAd)

    @Query("SELECT * FROM cached_ssp_ads WHERE mediaUrl = :mediaUrl LIMIT 1")
    suspend fun getCachedSspAd(mediaUrl: String): CachedSspAd?

    @Query("SELECT * FROM cached_ssp_ads ORDER BY lastAccessed ASC")
    suspend fun getAllCachedSspAds(): List<CachedSspAd>

    @Query("DELETE FROM cached_ssp_ads WHERE mediaUrl = :mediaUrl")
    suspend fun deleteCachedSspAd(mediaUrl: String)

    @Query("DELETE FROM cached_ssp_ads WHERE expiresAt < :now")
    suspend fun deleteExpiredSspAds(now: Long)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertPendingBeacon(beacon: PendingBeacon)

    @Query("SELECT * FROM pending_beacons ORDER BY createdAt ASC")
    suspend fun getAllPendingBeacons(): List<PendingBeacon>

    @Query("DELETE FROM pending_beacons WHERE id = :id")
    suspend fun deletePendingBeacon(id: Long)

    @Query("UPDATE pending_beacons SET retryCount = retryCount + 1 WHERE id = :id")
    suspend fun incrementRetryCount(id: Long)

    @Query("DELETE FROM cached_ssp_ads")
    suspend fun clearCachedSspAds()

    @Query("DELETE FROM pending_beacons")
    suspend fun clearPendingBeacons()
}
