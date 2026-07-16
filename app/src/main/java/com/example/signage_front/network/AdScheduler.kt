package com.example.signage_front.network

//AdScheduler.kt

import android.content.Context
import android.util.Log
import com.example.signage_front.data.AdRepository
import com.example.signage_front.data.AdStatus
import com.example.signage_front.data.AdDisplayLog
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
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var isPolling = false

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
        var othersChanged = false
        val pendingTokens = mutableMapOf<String, String>()
        val otherTableNames = mutableListOf<String>()

        for (i in 0 until statusArray.length()) {
            val obj = statusArray.getJSONObject(i)
            val tableName = obj.getString("table_name")
            val serverToken = obj.getString("timestamp")

            val localState = repository.getSyncState(tableName)
            
            if (localState == null || localState.timestamp != serverToken) {
                Log.d(TAG, "Change detected in $tableName. Server: $serverToken, Local: ${localState?.timestamp ?: "None"}")
                pendingTokens[tableName] = serverToken
                if (tableName == "ad_registry") {
                    adRegistryChanged = true
                } else {
                    othersChanged = true
                    otherTableNames.add(tableName)
                }
            }
        }

        // 1. Sync Config if any "other" table changed
        if (othersChanged) {
            Log.d(TAG, "Syncing app configuration...")
            if (fetchAndSyncConfig(context)) {
                otherTableNames.forEach { table ->
                    pendingTokens[table]?.let { token ->
                        repository.updateSyncState(table, token)
                    }
                }
                Log.d(TAG, "Config sync successful.")
            }
        }

        // 2. Sync Ad Registry (metadata + media)
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
                    true
                } else false
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to fetch config", e)
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
}
