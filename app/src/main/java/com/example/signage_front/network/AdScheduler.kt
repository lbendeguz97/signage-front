package com.example.signage_front.network

import android.content.Context
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import okhttp3.Request
import java.io.File

/**
 * Handles scheduled requests to the server, such as polling for ad status.
 */
object AdScheduler {
    private const val TAG = "AdScheduler"
    private const val AD_STATUS_FILE = "ad_status.json"
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
            fetchAndSaveAdStatus(context)
            
            // Background scheduling loop
            while (isActive) {
                delay(5 * 60 * 1000) // Wait 5 minutes
                fetchAndSaveAdStatus(context)
            }
        }
    }

    /**
     * Public method to manually trigger an update of the ad status.
     * Can be used to ensure the status is fresh before navigating.
     */
    suspend fun fetchAndSaveAdStatus(context: Context) {
        try {
            val jsonResponse = getAdStatusFromNetwork(context)
            if (jsonResponse != null) {
                Log.d(TAG, "Fetched new ad status, saving to file.")
                saveAdStatusToFile(context, jsonResponse)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to fetch or save ad status", e)
        }
    }

    /**
     * Fetches the current ad status from the server via mTLS.
     * Returns the raw JSON string (could be an Object or an Array).
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
     * Saves the JSON string to a local file, overwriting any previous version.
     */
    private fun saveAdStatusToFile(context: Context, jsonContent: String) {
        try {
            val file = File(context.filesDir, AD_STATUS_FILE)
            file.writeText(jsonContent)
            Log.d(TAG, "Ad status file updated at: ${file.absolutePath}")
        } catch (e: Exception) {
            Log.e(TAG, "Critical error saving ad status to file", e)
        }
    }
}
