package com.example.signage_front.network

//AdScheduler.kt

import android.content.Context
import android.util.Log
import com.example.signage_front.data.AdRepository
import com.example.signage_front.data.ContentOrchestrator
import com.example.signage_front.data.AdStatus
import com.example.signage_front.data.AdDisplayLog
import com.example.signage_front.data.PendingBeacon
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * Handles scheduled requests to the server, such as polling for ad status and configuration.
 */
object AdScheduler {
    private const val TAG = "AdScheduler"
    private const val CONFIG_FILE = "app_config.json"
    private const val SSP_REFRESH_MS = 2 * 60 * 1000L // force a refetch every 2 min so mock playlist changes propagate
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var isPolling = false
    private var lastSspPrefetchAt = 0L

    /**
     * Starts polling. It checks the database status first and syncs only if needed.
     */
    fun startPolling(context: Context) {
        if (isPolling) return
        isPolling = true
        
        startLogSync(context)
        
        scope.launch {
            while (isActive) {
                try {
                    checkAndSync(context)
                    flushPendingBeacons(context)
                    preFetchSspAdsIfNeeded(context)
                } catch (e: Exception) {
                    Log.e(TAG, "Error in polling loop", e)
                }
                delay(1 * 60 * 1000) // Check every 1 minute for changes
            }
        }
    }

    /**
     * Orchestrates the sync process by checking the global database status.
     * Tokens are compared as strings to avoid precision issues.
     */
    private suspend fun checkAndSync(context: Context) {
        Log.d(TAG, "Checking for database updates...")
        val statusJson = getDatabaseStatusFromNetwork(context) ?: return
        val statusArray = JSONArray(statusJson)
        val repository = AdRepository(context)

        var adRegistryChanged = false
        var groupPolicyChanged = false
        var sspConnectivityChanged = false
        
        val pendingTokens = mutableMapOf<String, String>()
        val groupPolicyTableNames = mutableListOf<String>()

        for (i in 0 until statusArray.length()) {
            val obj = statusArray.getJSONObject(i)
            val tableName = obj.getString("table_name")
            val serverToken = obj.getString("timestamp")

            val localState = repository.getSyncState(tableName)
            
            if (localState == null || localState.timestamp != serverToken) {
                Log.d(TAG, "Change detected in $tableName. Server: $serverToken, Local: ${localState?.timestamp ?: "None"}")
                pendingTokens[tableName] = serverToken
                when (tableName) {
                    "ad_registry" -> adRegistryChanged = true
                    "ssp_connectivities" -> sspConnectivityChanged = true
                    else -> {
                        groupPolicyChanged = true
                        groupPolicyTableNames.add(tableName)
                    }
                }
            }
        }

        // 1. Sync Config if any group policy table changed
        if (groupPolicyChanged) {
            Log.d(TAG, "Syncing app configuration...")
            if (fetchAndSyncConfig(context)) {
                groupPolicyTableNames.forEach { table ->
                    pendingTokens[table]?.let { token ->
                        repository.updateSyncState(table, token)
                    }
                }
                Log.d(TAG, "Config sync successful.")
                ContentOrchestrator.onConfigSynced(context)
            }
        }

        // 2. Sync SSP Connectivities
        if (sspConnectivityChanged) {
            Log.d(TAG, "Syncing SSP connectivities...")
            if (fetchAndSyncSspConnectivity(context)) {
                pendingTokens["ssp_connectivities"]?.let { token ->
                    repository.updateSyncState("ssp_connectivities", token)
                }
                Log.d(TAG, "SSP connectivities sync successful.")
            }
        }

        // 3. Sync Ad Registry (metadata + media)
        // We sync if the server registry changed OR if we have local ads that failed to sync previously
        val localAds = repository.adDao.getAllAdsList()
        val hasIncompleteAds = localAds.any { it.syncStatus != "VERIFIED" }

        if (adRegistryChanged) {
            Log.d(TAG, "Server registry changed. Fetching new ad list...")
            if (fetchAndSyncAdStatus(context)) {
                pendingTokens["ad_registry"]?.let { token ->
                    repository.updateSyncState("ad_registry", token)
                    Log.d(TAG, "Ad registry sync successful.")
                }
            }
        } else if (hasIncompleteAds) {
            Log.d(TAG, "Registry unchanged, but found incomplete local ads. Retrying sync...")
            // Pass existing list back to repository.syncAds to retry downloads/verification
            repository.syncAds(localAds)
        } else {
            Log.d(TAG, "Ad registry is up to date and all media is verified.")
        }
    }

    private suspend fun getDatabaseStatusFromNetwork(context: Context): String? {
        if (!SecurityManager.hasValidKey()) return null
        val client = NetworkClientProvider.getMTlsClient(context)
        val request = Request.Builder()
            .url("${Config.currentBaseUrl}/getDatabaseStatus")
            .get()
            .build()

        return try {
            client.newCall(request).execute().use { response ->
                if (response.isSuccessful) response.body?.string() else null
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to fetch database status", e)
            null
        }
    }

    private suspend fun fetchAndSyncConfig(context: Context): Boolean {
        if (!SecurityManager.hasValidKey()) return false
        val client = NetworkClientProvider.getMTlsClient(context)
        val request = Request.Builder()
            .url("${Config.currentBaseUrl}/getConfig")
            .get()
            .build()

        return try {
            client.newCall(request).execute().use { response ->
                if (response.isSuccessful) {
                    val configJson = response.body?.string() ?: return false
                    val configFile = File(context.filesDir, CONFIG_FILE)
                    configFile.writeText(configJson)
                    
                    val repository = AdRepository(context)
                    repository.syncConfig(configJson)
                } else false
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to fetch config", e)
            false
        }
    }

    private suspend fun fetchAndSyncSspConnectivity(context: Context): Boolean {
        if (!SecurityManager.hasValidKey()) return false
        val client = NetworkClientProvider.getMTlsClient(context)
        val request = Request.Builder()
            .url("${Config.currentBaseUrl}/getSspConnectivity")
            .get()
            .build()

        return try {
            client.newCall(request).execute().use { response ->
                if (response.isSuccessful) {
                    val sspJson = response.body?.string() ?: return false
                    val repository = AdRepository(context)
                    repository.syncSspConnectivity(sspJson)
                } else false
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to fetch SSP connectivities", e)
            false
        }
    }

    suspend fun fetchAndSyncAdStatus(context: Context): Boolean = kotlinx.coroutines.withContext(Dispatchers.IO) {
        val jsonResponse = getAdStatusFromNetwork(context) ?: return@withContext false
        syncAdStatus(context, jsonResponse)
    }

    private fun getAdStatusFromNetwork(context: Context): String? {
        if (!SecurityManager.hasValidKey()) return null
        val client = NetworkClientProvider.getMTlsClient(context)
        val request = Request.Builder()
            .url("${Config.currentBaseUrl}/getAdStatus")
            .get()
            .build()

        return try {
            client.newCall(request).execute().use { response ->
                if (response.isSuccessful) response.body?.string() else null
            }
        } catch (e: Exception) {
            Log.e(TAG, "Network error during getAdStatus", e)
            null
        }
    }

    private suspend fun syncAdStatus(context: Context, jsonContent: String): Boolean {
        return try {
            val jsonArray = JSONArray(jsonContent)
            val adList = mutableListOf<AdStatus>()

            for (i in 0 until jsonArray.length()) {
                val obj = jsonArray.getJSONObject(i)

                // Skip soft-deleted ads
                if (obj.has("deleted_at") && !obj.isNull("deleted_at")) {
                    Log.d(TAG, "Skipping soft-deleted ad: id=${obj.optString("ad_id")}")
                    continue
                }

                val mediaType = if (obj.has("media_type")) {
                    obj.getString("media_type")
                } else {
                    inferMediaType(obj.getString("path"))
                }

                adList.add(
                    AdStatus(
                        adId = obj.getString("ad_id"),
                        adAllowed = obj.getBoolean("ad_allowed"),
                        adult = obj.getBoolean("adult"),
                        path = obj.getString("path"),
                        url = if (obj.isNull("url")) null else obj.getString("url"),
                        display = obj.getString("display"),
                        displayTime = if (obj.isNull("display_time")) null else obj.getInt("display_time"),
                        mediaType = mediaType,
                        expectedChecksum = if (obj.isNull("checksum")) null else obj.getString("checksum"),
                        expectedSize = if (obj.isNull("size")) 0L else obj.getLong("size")
                    )
                )
            }

            val repository = AdRepository(context)
            repository.syncAds(adList)
        } catch (e: Exception) {
            Log.e(TAG, "Error during ad sync process", e)
            false
        }
    }

    private fun inferMediaType(path: String): String {
        val type = when {
            path.endsWith(".mp4", ignoreCase = true) || 
            path.endsWith(".mkv", ignoreCase = true) ||
            path.endsWith(".webm", ignoreCase = true) ||
            path.endsWith(".avi", ignoreCase = true) -> "video"
            
            path.endsWith(".jpg", ignoreCase = true) || 
            path.endsWith(".jpeg", ignoreCase = true) ||
            path.endsWith(".png", ignoreCase = true) ||
            path.endsWith(".gif", ignoreCase = true) ||
            path.endsWith(".webp", ignoreCase = true) ||
            path.endsWith(".bmp", ignoreCase = true) -> "image"
            
            path.endsWith(".html", ignoreCase = true) ||
            path.endsWith(".htm", ignoreCase = true) -> "html"
            
            else -> {
                Log.w(TAG, "Unknown media type for path: $path, defaulting to 'image'")
                "image"  // Default to image instead of "other" to prevent black screens
            }
        }
        Log.d(TAG, "Inferred mediaType='$type' for path: $path")
        return type
    }

    fun getLogSyncTime(context: Context): Int {
        val configFile = File(context.filesDir, CONFIG_FILE)
        if (!configFile.exists()) return -1
        return try {
            val json = JSONObject(configFile.readText())
            val configObj = json.optJSONObject("config")
            configObj?.optInt("logSyncTime", -1) ?: json.optInt("logSyncTime", -1)
        } catch (e: Exception) {
            -1
        }
    }

    private var isLogSyncActive = false
    fun startLogSync(context: Context) {
        if (isLogSyncActive) return
        isLogSyncActive = true
        scope.launch {
            while (isActive) {
                val logSyncTime = getLogSyncTime(context)
                if (logSyncTime > 0) {
                    try {
                        uploadPendingLogs(context)
                    } catch (e: Exception) {
                        Log.e(TAG, "Error in periodic log sync", e)
                    }
                    delay(logSyncTime * 1000L)
                } else {
                    delay(10 * 1000L) // check again in 10s if setting changed
                }
            }
        }
    }

    suspend fun uploadPendingLogs(context: Context) {
        val repository = AdRepository(context)
        val pendingLogs = repository.adDisplayLogDao.getPendingLogs()
        if (pendingLogs.isEmpty()) return

        Log.d(TAG, "Attempting to sync ${pendingLogs.size} display logs...")
        if (!SecurityManager.hasValidKey()) return

        val client = NetworkClientProvider.getMTlsClient(context)
        val jsonArray = JSONArray()
        pendingLogs.forEach { log ->
            val obj = JSONObject().apply {
                put("ad_id", log.adId)
                put("timestamp", log.timestamp)
                put("duration_ms", log.durationMs)
                put("clicked", log.clicked)
                put("exited_screen", log.exitedScreen)
                if (log.audienceAge != null) put("audience_age", log.audienceAge)
                if (log.audienceGender != null) put("audience_gender", log.audienceGender)
            }
            jsonArray.put(obj)
        }

        val requestBody = jsonArray.toString().toRequestBody("application/json".toMediaType())
        val request = Request.Builder()
            .url("${Config.currentBaseUrl}/uploadLogs")
            .post(requestBody)
            .build()

        try {
            client.newCall(request).execute().use { response ->
                if (response.isSuccessful || response.code == 204 || response.code == 200) {
                    val ids = pendingLogs.map { it.id }
                    repository.adDisplayLogDao.markLogsSynced(ids)
                    repository.adDisplayLogDao.deleteSyncedLogs() // Cleanup synced logs
                    Log.i(TAG, "Successfully synced and cleaned up ${pendingLogs.size} logs.")
                } else {
                    Log.w(TAG, "Log upload failed with code ${response.code}: ${response.body?.string()}")
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error uploading logs to server", e)
        }
    }

    suspend fun flushPendingBeacons(context: Context) {
        val repository = AdRepository(context)
        val beacons = repository.configDao.getAllPendingBeacons()
        if (beacons.isEmpty()) return

        Log.d(TAG, "Attempting to flush ${beacons.size} pending tracking beacons...")
        val client = NetworkClientProvider.getMTlsClient(context)

        beacons.forEach { beacon ->
            val request = Request.Builder()
                .url(beacon.url)
                .get()
                .build()

            try {
                client.newCall(request).execute().use { response ->
                    if (response.isSuccessful) {
                        repository.configDao.deletePendingBeacon(beacon.id)
                        Log.d(TAG, "Successfully flushed beacon: ${beacon.url}")
                    } else {
                        handleBeaconFailure(repository, beacon)
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to flush beacon: ${beacon.url}", e)
                handleBeaconFailure(repository, beacon)
            }
        }
    }

    private suspend fun handleBeaconFailure(repository: AdRepository, beacon: PendingBeacon) {
        if (beacon.retryCount >= 10) {
            repository.configDao.deletePendingBeacon(beacon.id)
            Log.w(TAG, "Dropped dead beacon after 10 retries: ${beacon.url}")
        } else {
            repository.configDao.incrementRetryCount(beacon.id)
        }
    }

    suspend fun preFetchSspAdsIfNeeded(context: Context) {
        val repository = AdRepository(context)

        val now = System.currentTimeMillis()
        val cachedAds = repository.configDao.getAllCachedSspAds().filter { it.expiresAt > now }

        // Skip the short-circuit below so mock playlist/creative changes are
        // picked up within a few minutes even while the cache is still "full".
        // Refetching the same rotation returns the same mediaUrls, which are
        // deduped by CachedSspAd's primary key, so the cache never grows unbounded.
        val forceRefresh = now - lastSspPrefetchAt >= SSP_REFRESH_MS

        // 1. Normal path: only prefetch when we have fewer than 3 valid cached ads
        if (cachedAds.size >= 3 && !forceRefresh) {
            return
        }
        lastSspPrefetchAt = now

        // 2. Read Group Configuration to see if we have an active sspConnectivityId
        val groupConfig = repository.configDao.getGroupConfig() ?: return
        val sspConnectivityId = groupConfig.sspConnectivityId ?: return

        // 3. Read SSP Connectivity parameters
        val sspConnectivity = repository.configDao.getSspConnectivity(sspConnectivityId) ?: return

        // 4. Read Tablet Metadata
        val androidId = android.provider.Settings.Secure.getString(
            context.contentResolver, android.provider.Settings.Secure.ANDROID_ID
        ) ?: "unknown"
        val tabletMetadata = repository.configDao.getTabletMetadata(androidId)
        val refId = tabletMetadata?.refId ?: sspConnectivity.refId

        Log.d(TAG, "Pre-fetching programmatic ad from SSP: ${sspConnectivity.endpointUrl} (uuid=$refId)...")

        val success = SspCacheManager.fetchAndCacheAd(
            context = context,
            connectivity = sspConnectivity,
            tabletRefId = refId
        )
        if (success) {
            Log.d(TAG, "Successfully pre-fetched programmatic ad.")
        } else {
            Log.w(TAG, "Failed to pre-fetch programmatic ad from SSP.")
        }
    }
}
