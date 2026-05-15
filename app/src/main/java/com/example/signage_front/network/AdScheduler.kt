package com.example.signage_front.network

import android.content.Context
import android.util.Log
import com.example.signage_front.data.AdRepository
import com.example.signage_front.data.AdStatus
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import okhttp3.Request
import org.json.JSONArray
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

    suspend fun fetchAndSyncAdStatus(context: Context): Boolean {
        val jsonResponse = getAdStatusFromNetwork(context) ?: return false
        return syncAdStatus(context, jsonResponse)
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
        return when {
            path.endsWith(".mp4", ignoreCase = true) || path.endsWith(".mkv", ignoreCase = true) -> "video"
            path.endsWith(".jpg", ignoreCase = true) || path.endsWith(".png", ignoreCase = true) -> "image"
            path.endsWith(".html", ignoreCase = true) -> "html"
            else -> "other"
        }
    }
}
