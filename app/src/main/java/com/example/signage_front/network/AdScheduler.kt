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

/**
 * Handles scheduled requests to the server, such as polling for ad status.
 */
object AdScheduler {
    private const val TAG = "AdScheduler"
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var isPolling = false

    /**
     * Starts polling for ad status. It performs one immediate request
     * and then schedules subsequent requests every 5 minutes in the background.
     */
    fun startPolling(context: Context) {
        if (isPolling) return
        isPolling = true
        
        scope.launch {
            // First execution happens immediately
            fetchAndSyncAdStatus(context)
            
            // Background scheduling loop
            while (isActive) {
                delay(5 * 60 * 1000) // Wait 5 minutes
                fetchAndSyncAdStatus(context)
            }
        }
    }

    /**
     * Public method to manually trigger a sync of the ad status.
     */
    suspend fun fetchAndSyncAdStatus(context: Context) {
        try {
            val jsonResponse = getAdStatusFromNetwork(context)
            if (jsonResponse != null) {
                Log.d(TAG, "Fetched new ad status, syncing with database and downloading media.")
                syncAdStatus(context, jsonResponse)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to fetch or sync ad status", e)
        }
    }

    /**
     * Fetches the current ad status from the server via mTLS.
     * Returns the raw JSON string.
     */
    private fun getAdStatusFromNetwork(context: Context): String? {
        if (!SecurityManager.hasValidKey()) return null

        val client = NetworkClientProvider.getMTlsClient(context)
        val request = Request.Builder()
            .url("${Config.BASE_URL}/getAdStatus")
            .get()
            .build()

        return try {
            client.newCall(request).execute().use { response ->
                if (response.isSuccessful) {
                    response.body?.string()
                } else {
                    Log.e(TAG, "getAdStatus network call failed: ${response.code}")
                    null
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Network error during getAdStatus", e)
            null
        }
    }

    /**
     * Parses the JSON response, syncs it with the Room database, and manages media downloads.
     */
    private suspend fun syncAdStatus(context: Context, jsonContent: String) {
        try {
            val jsonArray = JSONArray(jsonContent)
            val adList = mutableListOf<AdStatus>()

            for (i in 0 until jsonArray.length()) {
                val obj = jsonArray.getJSONObject(i)
                // Determine media type - assuming it might come from server, or we infer it
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
                        display = obj.getString("display"),
                        displayTime = if (obj.isNull("display_time")) null else obj.getInt("display_time"),
                        mediaType = mediaType
                    )
                )
            }

            // 1. Sync database
            val repository = AdRepository(context)
            repository.syncAds(adList)
            
            // 2. Download missing media files
            adList.forEach { ad ->
                MediaManager.downloadMediaIfNeeded(context, ad)
            }

            // 3. Cleanup orphaned files
            MediaManager.cleanupOrphanedMedia(context, adList)

            Log.d(TAG, "Sync and Media Download complete. Total ads: ${adList.size}")
        } catch (e: Exception) {
            Log.e(TAG, "Error during sync or download process", e)
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
