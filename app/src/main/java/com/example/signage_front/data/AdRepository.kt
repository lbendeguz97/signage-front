package com.example.signage_front.data

import android.content.Context
import android.util.Log
import com.example.signage_front.network.MediaManager
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONArray
import org.json.JSONObject

class AdRepository(private val context: Context) {
    private val TAG = "AdRepository"
    private val database = AppDatabase.getDatabase(context)
    val adDao = database.adDao()
    val adDisplayLogDao = database.adDisplayLogDao()
    private val syncDao = database.syncDao()
    val configDao = database.configDao()

    /** Records how an SSP/idle slot was filled so overrun can be measured
     *  and auto-adjusted later. */
    suspend fun logSspSlot(budgetMs: Long, filledMs: Long, overrunMs: Long, adsPlayed: Int, fallbackUsed: Boolean) {
        try {
            database.sspSlotLogDao().insert(
                SspSlotLog(
                    timestamp = System.currentTimeMillis(),
                    budgetMs = budgetMs,
                    filledMs = filledMs,
                    overrunMs = overrunMs,
                    adsPlayed = adsPlayed,
                    fallbackUsed = fallbackUsed
                )
            )
        } catch (e: Exception) {
            Log.e(TAG, "Failed to log SSP slot", e)
        }
    }

    companion object {
        private val adSyncMutex = Mutex()
        private val configSyncMutex = Mutex()
    }

    fun getAllAds(): Flow<List<AdStatus>> = adDao.getAllAds()

    /**
     * Performs a full sync of ads and manages media files.
     * Returns true only if the database sync and ALL media downloads/verifications succeeded.
     */
    suspend fun syncAds(ads: List<AdStatus>): Boolean = adSyncMutex.withLock {
        try {
            // 1. Merge status from local DB to avoid overwriting "VERIFIED" with "PENDING"
            val existingAds = adDao.getAllAdsList().associateBy { it.adId }
            val mergedAds = ads.map { incoming ->
                val existing = existingAds[incoming.adId]
                // If the ad exists and the checksum is the same, keep the local sync status
                if (existing != null && existing.expectedChecksum == incoming.expectedChecksum) {
                    incoming.copy(syncStatus = existing.syncStatus)
                } else {
                    incoming
                }
            }

            // 2. Sync Database records (Transaction)
            adDao.syncAds(mergedAds)
            
            // 3. Download and Verify media for anything not yet VERIFIED
            var allMediaSynced = true
            val adsToProcess = adDao.getAllAdsList()
            
            adsToProcess.forEach { ad ->
                if (ad.syncStatus == "VERIFIED") return@forEach

                // SSP / Idle virtual slots have no media file — nothing to
                // download or verify, so mark them ready immediately.
                val adMediaType = ad.mediaType?.lowercase()
                if (adMediaType == "ssp" || adMediaType == "idle") {
                    adDao.updateSyncStatus(ad.adId, "VERIFIED")
                    Log.d(TAG, "Ad ${ad.adId} is a virtual slot (type=$adMediaType) — marked verified, no media to download.")
                    return@forEach
                }

                try {
                    adDao.updateSyncStatus(ad.adId, "DOWNLOADING")
                    
                    val downloadSuccess = MediaManager.downloadMediaIfNeeded(context, ad)
                    if (downloadSuccess) {
                        val localFile = MediaManager.getLocalFile(context, ad)
                        val isVerified = MediaManager.verifyChecksum(localFile, ad.expectedChecksum)
                        
                        if (isVerified) {
                            adDao.updateSyncStatus(ad.adId, "VERIFIED")
                            Log.d(TAG, "Ad ${ad.adId} verified and ready.")
                        } else {
                            // If verification fails, delete the file to force a redownload next time
                            if (localFile.exists()) localFile.delete()
                            adDao.updateSyncStatus(ad.adId, "ERROR")
                            Log.e(TAG, "Checksum mismatch for ad ${ad.adId}. File deleted.")
                            allMediaSynced = false
                        }
                    } else {
                        adDao.updateSyncStatus(ad.adId, "ERROR")
                        Log.e(TAG, "Failed to download media for ad ${ad.adId}")
                        allMediaSynced = false
                    }
                } catch (e: Exception) {
                    adDao.updateSyncStatus(ad.adId, "ERROR")
                    Log.e(TAG, "Error processing ad ${ad.adId}", e)
                    allMediaSynced = false
                }
            }
            
            // 4. Cleanup files for removed ads
            MediaManager.cleanupOrphanedMedia(context, ads)
            
            allMediaSynced
        } catch (e: Exception) {
            Log.e(TAG, "Fatal error during syncAds", e)
            false
        }
    }

    suspend fun getSyncState(tableName: String): SyncState? = syncDao.getSyncStateByTable(tableName)
    
    suspend fun updateSyncState(tableName: String, token: String) {
        syncDao.updateSyncState(SyncState(tableName, token))
    }

    suspend fun syncConfig(configJson: String): Boolean = configSyncMutex.withLock {
        try {
            val json = JSONObject(configJson)

            // 1. Group config
            val groupId = json.getLong("group_id")
            val name = json.getString("name")
            val configJsonObj = json.getJSONObject("config").toString()
            val playlistId = if (json.isNull("playlist_id")) null else json.getLong("playlist_id")
            val scheduleId = if (json.isNull("schedule_id")) null else json.getLong("schedule_id")
            val sspConnectivityId = if (json.isNull("ssp_connectivity_id")) null else json.getLong("ssp_connectivity_id")
            val groupConfig = GroupConfig(groupId, name, configJsonObj, playlistId, scheduleId, sspConnectivityId)

            // 1b. Tablet metadata
            val refId = if (json.isNull("ref_id")) null else json.getString("ref_id")
            val androidId = android.provider.Settings.Secure.getString(
                context.contentResolver, android.provider.Settings.Secure.ANDROID_ID
            ) ?: "unknown"
            val tabletMetadata = TabletMetadata(androidId, refId)

            // 2. Playlists & Playlist Ads
            val playlistsArray = json.getJSONArray("playlists")
            val playlistList = mutableListOf<Playlist>()
            val playlistAdList = mutableListOf<PlaylistAd>()

            for (i in 0 until playlistsArray.length()) {
                val pObj = playlistsArray.getJSONObject(i)
                val id = pObj.getLong("id")
                val pName = pObj.getString("name")
                playlistList.add(Playlist(id, pName))

                val adsArray = pObj.getJSONArray("ads")
                for (j in 0 until adsArray.length()) {
                    val adMapping = adsArray.getJSONObject(j)
                    val adId = adMapping.getLong("ad_id")
                    val position = adMapping.getInt("position")
                    val duration = adMapping.getInt("duration")
                    playlistAdList.add(PlaylistAd(id, adId, position, duration))
                }
            }

            // 3. Campaigns, Schedules, Intervals, Rules, Triggers
            val campaignsArray = json.optJSONArray("campaigns") ?: JSONArray()
            val campaignList = mutableListOf<Campaign>()
            val campaignScheduleList = mutableListOf<CampaignSchedule>()
            val scheduleList = mutableListOf<Schedule>()
            val intervalList = mutableListOf<ScheduleInterval>()
            val ruleList = mutableListOf<ScheduleRule>()
            val triggerList = mutableListOf<CampaignTrigger>()

            for (i in 0 until campaignsArray.length()) {
                val cObj = campaignsArray.getJSONObject(i)
                val cId = cObj.getLong("id")
                val cName = cObj.getString("name")
                val startDate = cObj.getString("start_date")
                val endDate = cObj.getString("end_date")
                campaignList.add(Campaign(cId, cName, startDate, endDate))

                // Schedules
                val cSchedules = cObj.optJSONArray("schedules") ?: JSONArray()
                for (j in 0 until cSchedules.length()) {
                    val sObj = cSchedules.getJSONObject(j)
                    val sId = sObj.getLong("id")
                    val sName = sObj.getString("name")
                    scheduleList.add(Schedule(sId, sName, cId))

                    // Per-day campaign schedule mapping (date -> schedule)
                    if (sObj.has("date") && !sObj.isNull("date")) {
                        val date = sObj.getString("date").take(10) // normalize to yyyy-MM-dd
                        campaignScheduleList.add(CampaignSchedule(cId, sId, date))
                    }

                    // Intervals
                    val sIntervals = sObj.optJSONArray("intervals") ?: JSONArray()
                    for (k in 0 until sIntervals.length()) {
                        val intObj = sIntervals.getJSONObject(k)
                        val intId = intObj.getLong("id")
                        val pId = intObj.getLong("playlist_id")
                        val startTime = intObj.getString("start_time")
                        val endTime = intObj.getString("end_time")
                        intervalList.add(ScheduleInterval(intId, sId, pId, startTime, endTime))
                    }

                    // Rules
                    val sRules = sObj.optJSONArray("rules") ?: JSONArray()
                    for (k in 0 until sRules.length()) {
                        val ruleObj = sRules.getJSONObject(k)
                        val ruleId = ruleObj.getLong("id")
                        val templateId = if (ruleObj.isNull("template_id")) null else ruleObj.getLong("template_id")
                        val ruleType = ruleObj.getString("rule_type")
                        val dayOfWeek = if (ruleObj.isNull("day_of_week")) null else ruleObj.getInt("day_of_week")
                        val specificDate = if (ruleObj.isNull("specific_date")) null else ruleObj.getString("specific_date")
                        ruleList.add(ScheduleRule(ruleId, sId, templateId, ruleType, dayOfWeek, specificDate))
                    }
                }

                // Triggers
                val cTriggers = cObj.optJSONArray("triggers") ?: JSONArray()
                for (j in 0 until cTriggers.length()) {
                    val tObj = cTriggers.getJSONObject(j)
                    val tId = tObj.getLong("id")
                    val tName = tObj.getString("name")
                    val tType = tObj.getString("type")
                    val condConfig = tObj.getJSONObject("condition_config").toString()
                    val actionType = tObj.getString("action_type")
                    val actionId = tObj.getLong("action_id")
                    val priority = tObj.getString("priority")
                    val cooldown = tObj.getInt("cooldown_minutes")
                    val enabled = tObj.getBoolean("enabled")
                    val date = if (tObj.has("date") && !tObj.isNull("date")) tObj.getString("date").take(10) else ""
                    triggerList.add(CampaignTrigger(tId, tName, tType, condConfig, actionType, actionId, priority, cooldown, enabled, cId, date))
                }
            }

            // 4. Default Group Schedule (if present)
            if (json.has("default_schedule") && !json.isNull("default_schedule")) {
                val dsObj = json.getJSONObject("default_schedule")
                val dsId = dsObj.getLong("id")
                val dsName = dsObj.getString("name")
                scheduleList.add(Schedule(dsId, dsName, null))

                // Default Schedule Intervals
                val dsIntervals = dsObj.optJSONArray("intervals") ?: JSONArray()
                for (k in 0 until dsIntervals.length()) {
                    val intObj = dsIntervals.getJSONObject(k)
                    val intId = intObj.getLong("id")
                    val pId = intObj.getLong("playlist_id")
                    val startTime = intObj.getString("start_time")
                    val endTime = intObj.getString("end_time")
                    intervalList.add(ScheduleInterval(intId, dsId, pId, startTime, endTime))
                }

                // Default Schedule Rules
                val dsRules = dsObj.optJSONArray("rules") ?: JSONArray()
                for (k in 0 until dsRules.length()) {
                    val ruleObj = dsRules.getJSONObject(k)
                    val ruleId = ruleObj.getLong("id")
                    val templateId = if (ruleObj.isNull("template_id")) null else ruleObj.getLong("template_id")
                    val ruleType = ruleObj.getString("rule_type")
                    val dayOfWeek = if (ruleObj.isNull("day_of_week")) null else ruleObj.getInt("day_of_week")
                    val specificDate = if (ruleObj.isNull("specific_date")) null else ruleObj.getString("specific_date")
                    ruleList.add(ScheduleRule(ruleId, dsId, templateId, ruleType, dayOfWeek, specificDate))
                }
            }

            // Perform atomic transaction sync
            configDao.syncConfiguration(
                groupConfig,
                tabletMetadata,
                playlistList,
                playlistAdList,
                campaignList,
                campaignScheduleList,
                scheduleList,
                intervalList,
                ruleList,
                triggerList
            )
            Log.d(TAG, "Configuration database sync successful.")
            true
        } catch (e: Exception) {
            Log.e(TAG, "Failed to parse or sync configuration", e)
            false
        }
    }

    suspend fun syncSspConnectivity(sspJson: String): Boolean = configSyncMutex.withLock {
        try {
            val array = JSONArray(sspJson)
            val list = mutableListOf<SspConnectivity>()
            for (i in 0 until array.length()) {
                val obj = array.getJSONObject(i)
                val id = obj.getLong("id")
                val name = obj.getString("name")
                val provider = obj.getString("provider")
                val endpointUrl = obj.getString("endpoint_url")
                val refId = if (obj.isNull("ref_id")) null else obj.getString("ref_id")
                val dealId = if (obj.isNull("deal_id")) null else obj.getString("deal_id")
                val lineItemId = if (obj.isNull("line_item_id")) null else obj.getString("line_item_id")
                val additionalParams = obj.getJSONObject("additional_params").toString()
                val apiKey = if (obj.isNull("api_key")) null else obj.getString("api_key")

                list.add(SspConnectivity(id, name, provider, endpointUrl, refId, dealId, lineItemId, additionalParams, apiKey))
            }
            configDao.syncSspConnectivities(list)
            Log.d(TAG, "SSP connectivities database sync successful.")
            true
        } catch (e: Exception) {
            Log.e(TAG, "Failed to parse or sync SSP connectivities", e)
            false
        }
    }

    suspend fun clearStatus() {
        adDao.clearAdStatus()
        syncDao.clearAllSyncStates()
        configDao.clearGroupConfig()
        configDao.clearTabletMetadata()
        configDao.clearPlaylists()
        configDao.clearPlaylistAds()
        configDao.clearCampaigns()
        configDao.clearSchedules()
        configDao.clearScheduleIntervals()
        configDao.clearScheduleRules()
        configDao.clearCampaignTriggers()
        configDao.clearSspConnectivities()
        configDao.clearCachedSspAds()
        configDao.clearPendingBeacons()
        MediaManager.cleanupOrphanedMedia(context, emptyList())
    }

    suspend fun insertDisplayLog(log: AdDisplayLog) {
        database.adDisplayLogDao().insertLog(log)
    }
}
