package com.example.signage_front.network

import android.content.Context
import android.util.Log
import com.example.signage_front.data.AdRepository
import com.example.signage_front.data.CachedSspAd
import com.example.signage_front.data.PendingBeacon
import com.example.signage_front.data.SspConnectivity
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream

object SspCacheManager {
    private const val TAG = "SspCacheManager"
    private const val CACHE_DIR = "ssp_cache"
    private const val MAX_CACHE_SIZE = 200 * 1024 * 1024L // 200MB
    private const val MAX_WRAPPER_HOPS = 5 // VAST wrapper chain limit (secondary ad servers)
    private const val DEFAULT_TTL_MINUTES = 15L

    fun getCacheDirectory(context: Context): File {
        val directory = File(context.filesDir, CACHE_DIR)
        if (!directory.exists()) {
            directory.mkdirs()
        }
        return directory
    }

    /**
     * Queries the SSP and caches the programmatic ad. Returns true when a
     * creative was cached, false when the slot should be skipped.
     *
     * Tries the VAST ad-serving flow first (LMX: GET with uuid/deal params and
     * a "Token" header) and falls back to the legacy JSON POST flow for
     * endpoints that do not answer with VAST XML (e.g. the mock SSP).
     */
    suspend fun fetchAndCacheAd(context: Context, connectivity: SspConnectivity, tabletRefId: String?): Boolean {
        val repository = AdRepository(context)

        // 1. Run Eviction to clean up expired files and keep storage under the limit
        evictExpiredAndLru(context)

        // 2. Try the VAST flow. A definitive VAST answer (fill or no-fill) wins;
        //    only a non-VAST answer defers to the legacy JSON endpoint.
        when (tryFetchVast(context, repository, connectivity, tabletRefId)) {
            VastAttempt.CACHED -> return true
            VastAttempt.NO_FILL -> return false
            VastAttempt.NOT_VAST -> { /* fall through to legacy flow */ }
        }

        // 3. Legacy JSON POST flow (mock SSP custom schema)
        return fetchAndCacheLegacyAd(context, connectivity, tabletRefId)
    }

    private enum class VastAttempt { CACHED, NO_FILL, NOT_VAST }

    private suspend fun tryFetchVast(
        context: Context,
        repository: AdRepository,
        connectivity: SspConnectivity,
        tabletRefId: String?
    ): VastAttempt {
        val client = NetworkClientProvider.getMTlsClient(context)
        val lmxHost = runCatching { java.net.URI(connectivity.endpointUrl).host }.getOrNull()

        // LMX inventory/deal-level ad serving: GET with uuid + deal query params
        val url = buildVastUrl(connectivity, tabletRefId) ?: return VastAttempt.NOT_VAST
        Log.d(TAG, "VAST ad request: $url (uuid=${tabletRefId ?: connectivity.refId ?: "n/a"})")

        var requestUrl: String = url
        var hops = 0
        val wrapperImpressions = mutableListOf<String>()
        val wrapperTracking = mutableMapOf<String, String>()

        try {
            while (hops <= MAX_WRAPPER_HOPS) {
                val requestBuilder = Request.Builder().url(requestUrl).get()
                // The LMX API key must not leak to secondary ad servers: only
                // attach the Token header to the configured LMX endpoint host.
                if (!connectivity.apiKey.isNullOrBlank() && hostOf(requestUrl) == lmxHost) {
                    requestBuilder.header("Token", connectivity.apiKey)
                }

                val response = client.newCall(requestBuilder.build()).execute()
                val body = response.body?.string()
                response.close()

                if (!response.isSuccessful) {
                    Log.w(TAG, "VAST request failed: HTTP ${response.code} from $requestUrl")
                    // A 204-style no-content answer from a VAST endpoint means no fill.
                    if (response.code == 204) return VastAttempt.NO_FILL
                    return if (body != null && looksLikeXml(body)) VastAttempt.NO_FILL else VastAttempt.NOT_VAST
                }

                val trimmed = body?.trim().orEmpty()
                if (!looksLikeXml(trimmed)) {
                    // JSON body etc. -> not a VAST endpoint, let the legacy flow handle it
                    return VastAttempt.NOT_VAST
                }

                val wrapperTarget = VastParser.wrapperTarget(trimmed)
                if (wrapperTarget != null) {
                    // Wrapper: merge wrapper-level trackers, then follow the chain
                    mergeTrackers(trimmed, wrapperImpressions, wrapperTracking)
                    hops++
                    if (hops > MAX_WRAPPER_HOPS) {
                        Log.w(TAG, "VAST wrapper chain exceeds $MAX_WRAPPER_HOPS hops, giving up")
                        return VastAttempt.NO_FILL
                    }
                    Log.d(TAG, "Following VAST wrapper ($hops/$MAX_WRAPPER_HOPS): $wrapperTarget")
                    requestUrl = wrapperTarget
                    continue
                }

                val ads = VastParser.parse(trimmed)
                if (ads.isEmpty()) return VastAttempt.NO_FILL

                // Cache the whole pod: each ad becomes its own cached creative.
                // Wrapper-level trackers fire alongside each pod ad's own trackers.
                val ttlMinutes = additionalParams(connectivity)["ttl_minutes"]?.toLongOrNull() ?: DEFAULT_TTL_MINUTES
                var cachedAny = false
                for (ad in ads) {
                    val mediaUrl = ad.mediaUrl ?: continue
                    val impressions = wrapperImpressions + ad.impressions
                    val tracking = LinkedHashMap(wrapperTracking).apply { putAll(ad.trackingEvents) }
                    val sspResponse = SspAdResponse(
                        mediaUrl = mediaUrl,
                        mediaType = ad.mediaType,
                        durationSeconds = ad.durationSeconds.takeIf { it > 0 } ?: 10,
                        impressionUrls = impressions.distinct(),
                        clickTrackingUrls = emptyList(),
                        redirectUrl = null,
                        expiresAt = System.currentTimeMillis() + ttlMinutes * 60 * 1000L,
                        trackingUrls = tracking
                    )
                    if (downloadSspMedia(context, sspResponse)) cachedAny = true
                }
                return if (cachedAny) VastAttempt.CACHED else VastAttempt.NO_FILL
            }
            return VastAttempt.NO_FILL
        } catch (e: Exception) {
            Log.e(TAG, "VAST fetch failed", e)
            return VastAttempt.NOT_VAST
        }
    }

    private fun buildVastUrl(connectivity: SspConnectivity, tabletRefId: String?): String? {
        val uuid = tabletRefId ?: connectivity.refId ?: return null
        return try {
            val urlBuilder = android.net.Uri.parse(connectivity.endpointUrl).buildUpon()
                .appendQueryParameter("uuid", uuid)
            connectivity.dealId?.let { urlBuilder.appendQueryParameter("deal", it) }
            additionalParams(connectivity).forEach { (key, value) ->
                if (key != "uuid" && key != "deal" && key != "ttl_minutes") {
                    urlBuilder.appendQueryParameter(key, value)
                }
            }
            urlBuilder.build().toString()
        } catch (e: Exception) {
            Log.e(TAG, "Failed to build VAST url from ${connectivity.endpointUrl}", e)
            null
        }
    }

    private fun additionalParams(connectivity: SspConnectivity): Map<String, String> {
        return try {
            val obj = JSONObject(connectivity.additionalParams)
            obj.keys().asSequence().map { it to obj.optString(it) }.toMap()
        } catch (e: Exception) {
            emptyMap()
        }
    }

    private fun looksLikeXml(text: String): Boolean {
        // Detect a real VAST document (root <VAST> element). Express-style HTML
        // 404/error pages also start with "<" but must NOT be treated as VAST,
        // otherwise a POST-only legacy endpoint would be misread as "no fill"
        // instead of falling back to the legacy JSON flow.
        val trimmed = text.trimStart()
        return trimmed.startsWith("<VAST", ignoreCase = true) || trimmed.startsWith("<?xml")
    }

    private fun hostOf(url: String): String? = runCatching { java.net.URI(url).host }.getOrNull()

    private fun mergeTrackers(xml: String, impressions: MutableList<String>, tracking: MutableMap<String, String>) {
        val trackers = VastParser.parseTrackers(xml)
        impressions += trackers.impressions
        trackers.trackingEvents.forEach { (event, url) -> tracking.putIfAbsent(event, url) }
    }

    /**
     * Legacy JSON POST flow for custom SSP endpoints (mock SSP schema).
     */
    private suspend fun fetchAndCacheLegacyAd(context: Context, connectivity: SspConnectivity, refId: String?): Boolean {
        val repository = AdRepository(context)
        val client = NetworkClientProvider.getMTlsClient(context)

        val params = try {
            JSONObject(connectivity.additionalParams)
        } catch (e: Exception) {
            JSONObject()
        }
        connectivity.dealId?.let { params.put("deal_id", it) }
        connectivity.lineItemId?.let { params.put("line_item_id", it) }

        val requestBodyJson = JSONObject().apply {
            put("ref_id", refId)
            params.keys().forEach { key ->
                put(key, params.get(key))
            }
        }
        val requestBody = requestBodyJson.toString().toRequestBody("application/json".toMediaType())
        val request = Request.Builder()
            .url(connectivity.endpointUrl)
            .post(requestBody)
            .build()

        return try {
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return@use false

                val responseBody = response.body?.string() ?: return@use false
                val sspResponse = parseSspResponse(responseBody) ?: return@use false

                // 3. Download the ad media
                downloadSspMedia(context, sspResponse)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to fetch or cache SSP ad", e)
            false
        }
    }

    private fun parseSspResponse(jsonStr: String): SspAdResponse? {
        return try {
            // General Schema Mapping from JSON response
            val json = JSONObject(jsonStr)
            val mediaUrl = json.getString("media_url")
            val mediaType = json.optString("media_type", "video")
            val duration = json.optInt("duration_seconds", 10)

            val impressions = mutableListOf<String>()
            val impressionArray = json.optJSONArray("impression_urls")
            if (impressionArray != null) {
                for (i in 0 until impressionArray.length()) {
                    impressions.add(impressionArray.getString(i))
                }
            }

            val clicks = mutableListOf<String>()
            val clickArray = json.optJSONArray("click_tracking_urls")
            if (clickArray != null) {
                for (i in 0 until clickArray.length()) {
                    clicks.add(clickArray.getString(i))
                }
            }

            val redirectUrl = if (json.isNull("redirect_url")) null else json.getString("redirect_url")

            // TTL default: 15 minutes
            val ttlMinutes = json.optLong("ttl_minutes", 15L)
            val expiresAt = System.currentTimeMillis() + (ttlMinutes * 60 * 1000L)

            SspAdResponse(mediaUrl, mediaType, duration, impressions, clicks, redirectUrl, expiresAt)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to parse SSP response JSON", e)
            null
        }
    }

    private suspend fun downloadSspMedia(context: Context, ad: SspAdResponse): Boolean {
        val repository = AdRepository(context)
        val cacheDir = getCacheDirectory(context)

        // Generate unique local file name based on URL hash
        val fileExtension = if (ad.mediaType == "video") "mp4" else "jpg"
        val fileName = "${ad.mediaUrl.hashCode()}.$fileExtension"
        val localFile = File(cacheDir, fileName)

        val client = NetworkClientProvider.getMTlsClient(context)
        val request = Request.Builder().url(ad.mediaUrl).get().build()

        return try {
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return@use false
                val body = response.body ?: return@use false

                FileOutputStream(localFile).use { output ->
                    body.byteStream().use { input ->
                        input.copyTo(output)
                    }
                }

                // Create CachedSspAd record
                val cachedAd = CachedSspAd(
                    mediaUrl = ad.mediaUrl,
                    localPath = localFile.absolutePath,
                    mediaType = ad.mediaType,
                    sizeBytes = localFile.length(),
                    expiresAt = ad.expiresAt,
                    lastAccessed = System.currentTimeMillis(),
                    durationSeconds = ad.durationSeconds
                )
                repository.configDao.insertCachedSspAd(cachedAd)

                // Store tracking details in an ad-hoc local format
                saveSspMetadata(context, ad)

                Log.d(TAG, "Successfully cached SSP ad: ${ad.mediaUrl} to ${localFile.absolutePath}")
                true
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error downloading SSP ad media", e)
            if (localFile.exists()) localFile.delete()
            false
        }
    }

    private fun saveSspMetadata(context: Context, ad: SspAdResponse) {
        val prefs = context.getSharedPreferences("ssp_metadata_prefs", Context.MODE_PRIVATE)
        val keyPrefix = "ssp_${ad.mediaUrl.hashCode()}_"
        prefs.edit().apply {
            putString("${keyPrefix}impressions", JSONArray(ad.impressionUrls).toString())
            putString("${keyPrefix}clicks", JSONArray(ad.clickTrackingUrls).toString())
            putString("${keyPrefix}redirect", ad.redirectUrl)
            putString("${keyPrefix}tracking", JSONObject(ad.trackingUrls).toString())
            putInt("${keyPrefix}duration", ad.durationSeconds)
            apply()
        }
    }

    fun getSspMetadata(context: Context, mediaUrl: String): SspMetadata? {
        val prefs = context.getSharedPreferences("ssp_metadata_prefs", Context.MODE_PRIVATE)
        val keyPrefix = "ssp_${mediaUrl.hashCode()}_"
        val impressionsStr = prefs.getString("${keyPrefix}impressions", null) ?: return null
        val clicksStr = prefs.getString("${keyPrefix}clicks", null) ?: "[]"
        val redirectUrl = prefs.getString("${keyPrefix}redirect", null)
        val trackingStr = prefs.getString("${keyPrefix}tracking", null)
        val durationSeconds = prefs.getInt("${keyPrefix}duration", 10)

        val impressions = mutableListOf<String>()
        val impArray = JSONArray(impressionsStr)
        for (i in 0 until impArray.length()) {
            impressions.add(impArray.getString(i))
        }

        val clicks = mutableListOf<String>()
        val clkArray = JSONArray(clicksStr)
        for (i in 0 until clkArray.length()) {
            clicks.add(clkArray.getString(i))
        }

        val tracking = linkedMapOf<String, String>()
        trackingStr?.let {
            try {
                val obj = JSONObject(it)
                obj.keys().forEach { event -> tracking[event] = obj.getString(event) }
            } catch (e: Exception) {
                Log.w(TAG, "Failed to parse tracking metadata", e)
            }
        }

        return SspMetadata(impressions, clicks, redirectUrl, tracking, durationSeconds)
    }

    /**
     * Cleans up expired files (TTL) and oldest files (LRU) to enforce the 200MB limit.
     */
    suspend fun evictExpiredAndLru(context: Context) {
        val repository = AdRepository(context)
        val now = System.currentTimeMillis()

        // 1. Delete Expired Ads
        repository.configDao.deleteExpiredSspAds(now)

        // Find orphaned files (files in ssp_cache directory that are not tracked in Room)
        val cacheDir = getCacheDirectory(context)
        val trackedAds = repository.configDao.getAllCachedSspAds()
        val trackedPaths = trackedAds.map { it.localPath }.toSet()

        cacheDir.listFiles()?.forEach { file ->
            if (!trackedPaths.contains(file.absolutePath)) {
                file.delete()
                Log.d(TAG, "Deleted orphaned cache file: ${file.name}")
            }
        }

        // 2. Enforce LRU Limit
        var totalSize = trackedAds.sumOf { it.sizeBytes }
        if (totalSize > MAX_CACHE_SIZE) {
            Log.d(TAG, "Cache size ($totalSize bytes) exceeds limit ($MAX_CACHE_SIZE bytes). Evicting LRU items...")
            for (ad in trackedAds) { // Sorted by lastAccessed ASC
                val file = File(ad.localPath)
                if (file.exists()) {
                    totalSize -= ad.sizeBytes
                    file.delete()
                    Log.d(TAG, "LRU Evicted file: ${file.name}")
                }
                repository.configDao.deleteCachedSspAd(ad.mediaUrl)
                if (totalSize <= MAX_CACHE_SIZE) break
            }
        }
    }

    suspend fun fireImpressionBeacons(context: Context, trackingUrls: List<String>) {
        val repository = AdRepository(context)
        val client = NetworkClientProvider.getMTlsClient(context)

        trackingUrls.forEach { url ->
            val request = Request.Builder().url(url).get().build()
            try {
                // Fire immediately in a background task
                client.newCall(request).execute().use { response ->
                    if (response.isSuccessful) {
                        Log.d(TAG, "Successfully fired impression beacon: $url")
                    } else {
                        queuePendingBeacon(repository, url)
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to fire impression beacon: $url. Queueing for retry.", e)
                queuePendingBeacon(repository, url)
            }
        }
    }

    private suspend fun queuePendingBeacon(repository: AdRepository, url: String) {
        try {
            repository.configDao.insertPendingBeacon(PendingBeacon(url = url))
        } catch (e: Exception) {
            Log.e(TAG, "Failed to queue pending beacon", e)
        }
    }
}

data class SspAdResponse(
    val mediaUrl: String,
    val mediaType: String,
    val durationSeconds: Int,
    val impressionUrls: List<String>,
    val clickTrackingUrls: List<String>,
    val redirectUrl: String?,
    val expiresAt: Long,
    val trackingUrls: Map<String, String> = emptyMap()
)

data class SspMetadata(
    val impressionUrls: List<String>,
    val clickTrackingUrls: List<String>,
    val redirectUrl: String?,
    val trackingUrls: Map<String, String> = emptyMap(),
    val durationSeconds: Int = 10
)
