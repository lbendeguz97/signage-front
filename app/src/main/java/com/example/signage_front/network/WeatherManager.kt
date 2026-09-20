package com.example.signage_front.network

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.location.LocationManager
import android.util.Log
import androidx.core.content.ContextCompat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject

/**
 * Fetches and caches the tablet's local weather from the signage server
 * (`GET /getWeather?lat&lon`). The server resolves the nearest Köpönyeg city,
 * fetches a detailed + weekly forecast and caches it, so this client can call it
 * on demand. The last payload is persisted to SharedPreferences and served when
 * the device is offline or has no location fix.
 */
object WeatherManager {
    private const val TAG = "WeatherManager"
    private const val PREFS = "weather_prefs"
    private const val KEY_PAYLOAD = "last_payload"
    private const val KEY_FETCHED_AT = "last_fetched_at"

    // Fallback when the device has no location fix (e.g. an emulator without GPS):
    // the centre of Budapest. The server resolves the nearest Köpönyeg city from this.
    private const val DEFAULT_LAT = 47.4979
    private const val DEFAULT_LON = 19.0402

    data class WeatherBundle(
        val cityName: String,
        val regionTitle: String?,
        val distanceKm: Double,
        val current: WeatherCurrent?,
        val hourly: List<WeatherHourlyDay>,
        val week: List<WeatherDay>,
        val fetchedAt: Long,
        val stale: Boolean
    )

    data class WeatherCurrent(
        val temperature: Double,
        val rain: Double,
        val humidity: Int,
        val pressure: Int,
        val windStrength: Double,
        val windDirection: String,
        val windChill: Double,
        val skyIcon: String,
        val frontalSystem: String?,
        val uvIndex: Int?,
        val uvTitle: String?
    )

    data class WeatherHourlyDay(val date: String, val hours: List<WeatherHour>)
    data class WeatherHour(val hour: Int, val temp: Double, val rain: Double, val icon: String)
    data class WeatherDay(
        val dayAfter: Int,
        val minTemp: Double,
        val maxTemp: Double,
        val wind: Double,
        val rain: Double,
        val icon: String,
        val rainfallType: String?
    )

    fun hasLocationPermission(context: Context): Boolean =
        ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED ||
            ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED

    /** Last known coarse location from GPS or network provider, if available. */
    fun lastKnownLocation(context: Context): Pair<Double, Double>? {
        if (!hasLocationPermission(context)) return null
        return try {
            val lm = context.getSystemService(Context.LOCATION_SERVICE) as? LocationManager ?: return null
            val loc = lm.getLastKnownLocation(LocationManager.GPS_PROVIDER)
                ?: lm.getLastKnownLocation(LocationManager.NETWORK_PROVIDER)
                ?: lm.getLastKnownLocation(LocationManager.PASSIVE_PROVIDER)
            loc?.let { it.latitude to it.longitude }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to read last known location", e)
            null
        }
    }

    /** Returns the persisted forecast (if any) without touching the network. */
    fun loadCached(context: Context): WeatherBundle? {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val json = prefs.getString(KEY_PAYLOAD, null) ?: return null
        val fetchedAt = prefs.getLong(KEY_FETCHED_AT, 0L)
        return try {
            parse(json, fetchedAt, stale = true)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to parse cached weather", e)
            null
        }
    }

    /**
     * Fetches weather for the device's last known location, falling back to the
     * centre of Budapest when there is no fix. Falls back to the cached payload on
     * any failure. Returns null when there is neither a fresh response nor a cache.
     */
    suspend fun refresh(context: Context): WeatherBundle? {
        val location = lastKnownLocation(context)
        val (lat, lon) = location ?: run {
            Log.d(TAG, "No location fix; using Budapest centre as fallback")
            DEFAULT_LAT to DEFAULT_LON
        }
        val fresh = fetch(context, lat, lon)
        return fresh ?: loadCached(context)
    }

    private suspend fun fetch(context: Context, lat: Double, lon: Double): WeatherBundle? = withContext(Dispatchers.IO) {
        if (!SecurityManager.hasValidKey()) {
            Log.w(TAG, "Skipping weather fetch: no valid client key")
            return@withContext null
        }
        val url = "${Config.currentBaseUrl}/getWeather?lat=$lat&lon=$lon"
        return@withContext try {
            val client = NetworkClientProvider.getMTlsClient(context)
            val request = Request.Builder().url(url).get().build()
            client.newCall(request).execute().use { response ->
                val body = response.body?.string()
                if (!response.isSuccessful || body.isNullOrBlank()) {
                    Log.w(TAG, "Weather request failed: HTTP ${response.code}")
                    return@withContext null
                }
                val fetchedAt = System.currentTimeMillis()
                val bundle = parse(body, fetchedAt, stale = false)
                persist(context, body, fetchedAt)
                bundle
            }
        } catch (e: Exception) {
            Log.e(TAG, "Weather request error", e)
            null
        }
    }

    private fun persist(context: Context, json: String, fetchedAt: Long) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putString(KEY_PAYLOAD, json)
            .putLong(KEY_FETCHED_AT, fetchedAt)
            .apply()
    }

    private fun parse(json: String, fetchedAt: Long, stale: Boolean): WeatherBundle {
        val root = JSONObject(json)
        val location = root.optJSONObject("location")
        val city = location?.optJSONObject("city")
        val region = location?.optJSONObject("region")

        val currentObj = root.optJSONObject("current")
        val current = currentObj?.let {
            val wind = it.optJSONObject("wind")
            val uv = it.optJSONObject("uv")
            WeatherCurrent(
                temperature = it.optDouble("temperature", Double.NaN),
                rain = it.optDouble("rain", 0.0),
                humidity = it.optInt("humidity", 0),
                pressure = it.optInt("pressure", 0),
                windStrength = wind?.optDouble("strength", 0.0) ?: 0.0,
                windDirection = wind?.optString("direction", "") ?: "",
                windChill = it.optDouble("windChill", Double.NaN),
                skyIcon = it.optString("skyIcon", ""),
                frontalSystem = it.optString("frontalSystem").takeIf { s -> s.isNotBlank() && s != "null" },
                uvIndex = uv?.optInt("index"),
                uvTitle = uv?.optString("title")?.takeIf { s -> s.isNotBlank() && s != "null" }
            )
        }

        val hourly = mutableListOf<WeatherHourlyDay>()
        root.optJSONArray("hourly")?.let { arr ->
            for (i in 0 until arr.length()) {
                val dayObj = arr.optJSONObject(i) ?: continue
                val hours = mutableListOf<WeatherHour>()
                dayObj.optJSONArray("hours")?.let { hArr ->
                    for (j in 0 until hArr.length()) {
                        val h = hArr.optJSONObject(j) ?: continue
                        hours.add(
                            WeatherHour(
                                hour = h.optInt("hour", 0),
                                temp = h.optDouble("temp", Double.NaN),
                                rain = h.optDouble("rain", 0.0),
                                icon = h.optString("icon", "")
                            )
                        )
                    }
                }
                hourly.add(WeatherHourlyDay(dayObj.optString("date", ""), hours))
            }
        }

        val week = mutableListOf<WeatherDay>()
        root.optJSONObject("daily")?.optJSONArray("week")?.let { arr: JSONArray ->
            for (i in 0 until arr.length()) {
                val d = arr.optJSONObject(i) ?: continue
                week.add(
                    WeatherDay(
                        dayAfter = d.optInt("dayAfter", i),
                        minTemp = d.optDouble("minTemp", Double.NaN),
                        maxTemp = d.optDouble("maxTemp", Double.NaN),
                        wind = d.optDouble("wind", 0.0),
                        rain = d.optDouble("rain", 0.0),
                        icon = d.optString("icon", ""),
                        rainfallType = d.optString("rainfallType").takeIf { s -> s.isNotBlank() && s != "null" }
                    )
                )
            }
        }

        val meta = root.optJSONObject("meta")
        return WeatherBundle(
            cityName = city?.optString("name", "") ?: "",
            regionTitle = region?.optString("title")?.takeIf { it.isNotBlank() && it != "null" },
            distanceKm = location?.optDouble("distanceKm", 0.0) ?: 0.0,
            current = current,
            hourly = hourly,
            week = week,
            fetchedAt = fetchedAt,
            stale = stale || (meta?.optBoolean("stale", false) ?: false)
        )
    }
}
