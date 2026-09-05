package com.example.signage_front.data

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.location.LocationManager
import android.util.Log
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.json.JSONObject
import java.time.LocalDate
import java.time.LocalTime
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Resolves the active content source by walking the priority chain (see ORCHESTRATION_PLAN §3):
 *
 * 1. Active triggers (from any active campaign)
 * 2. Campaign schedule for today
 * 3. Default group schedule
 * 4. Group SSP
 * 5. Default playlist
 * 6. Logo (static fallback)
 *
 * Re-evaluated every 30s and immediately after a successful config sync.
 */
object ContentOrchestrator {
    private const val TAG = "ContentOrchestrator"
    private const val RESOLVE_INTERVAL_MS = 30_000L

    // In-memory cooldown: triggerId → lastFiredAt epoch millis (resets on app restart, acceptable)
    private val triggerCooldowns = ConcurrentHashMap<Long, Long>()

    private val _state = MutableStateFlow(
        OrchestratorState(ResolvedContent.Logo, emptyList(), null)
    )
    val state: StateFlow<OrchestratorState> = _state.asStateFlow()

    private val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())

    fun start(context: Context) {
        scope.launch {
            while (isActive) {
                try {
                    resolve(context)
                } catch (e: Exception) {
                    Log.e(TAG, "Error during content resolution", e)
                }
                delay(RESOLVE_INTERVAL_MS)
            }
        }
    }

    /** Called by AdScheduler immediately after a successful config sync. */
    fun onConfigSynced(context: Context) {
        scope.launch {
            try {
                resolve(context)
            } catch (e: Exception) {
                Log.e(TAG, "Error during content resolution after config sync", e)
            }
        }
    }

    /** Called by AdScreen when a trigger finishes playing its playlist once. */
    fun onTriggerComplete(triggerId: Long) {
        triggerCooldowns[triggerId] = System.currentTimeMillis()
        val current = _state.value
        val newQueue = current.triggerQueue.filterNot { it.triggerId == triggerId }
        _state.value = current.copy(triggerQueue = newQueue, pendingInterrupt = null)
        Log.d(TAG, "Trigger $triggerId completed. Remaining queue: ${newQueue.map { it.triggerId }}")
    }

    private suspend fun resolve(context: Context) {
        val repo = AdRepository(context)
        val groupConfig = repo.configDao.getGroupConfig() ?: run {
            _state.value = OrchestratorState(ResolvedContent.Logo, emptyList(), null)
            return
        }

        // 1. Evaluate triggers
        val triggerItems = evaluateTriggers(context, repo)

        // 2. Resolve base content
        val base = resolveBase(repo, groupConfig)

        // 3. Compute pending interrupt for triggers not already in the queue
        val currentQueue = _state.value.triggerQueue
        val newTriggers = triggerItems.filterNot { new ->
            currentQueue.any { it.triggerId == new.triggerId }
        }
        val highestNewPriority = newTriggers.minByOrNull { priorityRank(it.priority) }

        _state.value = OrchestratorState(
            base = base,
            triggerQueue = triggerItems,
            pendingInterrupt = highestNewPriority
        )
    }

    private suspend fun evaluateTriggers(context: Context, repo: AdRepository): List<TriggerItem> {
        val today = LocalDate.now().toString()
        val activeCampaign = repo.configDao.getAllCampaigns().firstOrNull { c ->
            today >= normalizeDate(c.startDate) && today <= normalizeDate(c.endDate)
        } ?: run {
            Log.d(TAG, "No active campaign today — no triggers to evaluate")
            return emptyList()
        }

        val now = System.currentTimeMillis()
        val items = repo.configDao.getCampaignTriggersByDate(activeCampaign.campaignId, today)
            .filter { it.enabled }
            .filter { trigger ->
                val cooldownMs = trigger.cooldownMinutes * 60_000L
                val lastFired = triggerCooldowns[trigger.triggerId] ?: 0L
                now - lastFired >= cooldownMs
            }
            .filter { evaluateTriggerCondition(context, it) }
            .map { trigger ->
                TriggerItem(
                    triggerId = trigger.triggerId,
                    priority = trigger.priority,
                    actionType = trigger.actionType,
                    actionId = trigger.actionId,
                    firedAt = now
                )
            }
            .sortedBy { priorityRank(it.priority) }

        if (items.isNotEmpty()) {
            Log.d(TAG, "Active triggers for campaign ${activeCampaign.campaignId} on $today: ${items.map { "id=${it.triggerId}(${it.priority})" }}")
        }
        return items
    }

    private fun evaluateTriggerCondition(context: Context, trigger: CampaignTrigger): Boolean = when (trigger.type) {
        "location" -> evaluateLocation(context, trigger.conditionConfig)
        "weather" -> false // Not yet implemented (Phase 5)
        else -> false
    }

    /** Coarse GPS check: returns true only if the tablet is within [radius] meters of the configured point. */
    private fun evaluateLocation(context: Context, conditionConfig: String): Boolean {
        return try {
            if (ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_COARSE_LOCATION)
                != PackageManager.PERMISSION_GRANTED
            ) {
                Log.d(TAG, "Location trigger: no location permission — not firing")
                return false
            }
            val config = JSONObject(conditionConfig)
            val targetLat = config.optDouble("lat", Double.NaN)
            val targetLon = config.optDouble("lon", Double.NaN)
            val radius = config.optDouble("radius", 0.0)
            if (targetLat.isNaN() || targetLon.isNaN() || radius <= 0.0) return false

            val lm = context.getSystemService(Context.LOCATION_SERVICE) as? LocationManager ?: return false
            val location = lm.getLastKnownLocation(LocationManager.GPS_PROVIDER)
                ?: lm.getLastKnownLocation(LocationManager.NETWORK_PROVIDER)
                ?: return false

            val distance = haversineMeters(targetLat, targetLon, location.latitude, location.longitude)
            val fired = distance <= radius
            Log.d(TAG, "Location trigger: distance=${distance}m radius=${radius}m fired=$fired")
            fired
        } catch (e: Exception) {
            Log.e(TAG, "Location evaluation failed", e)
            false
        }
    }

    private fun haversineMeters(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val r = 6371000.0
        val dLat = Math.toRadians(lat2 - lat1)
        val dLon = Math.toRadians(lon2 - lon1)
        val a = sin(dLat / 2) * sin(dLat / 2) +
                cos(Math.toRadians(lat1)) * cos(Math.toRadians(lat2)) *
                sin(dLon / 2) * sin(dLon / 2)
        return r * 2 * atan2(sqrt(a), sqrt(1 - a))
    }

    private fun priorityRank(priority: String): Int = when (priority) {
        "high" -> 0
        "medium" -> 1
        else -> 2
    }

    private suspend fun resolveBase(repo: AdRepository, groupConfig: GroupConfig): ResolvedContent {
        val today = LocalDate.now().toString() // yyyy-MM-dd
        val now = LocalTime.now().toString() // HH:mm:ss(.ffffff)

        // Level 2: Campaign schedule for today
        val activeCampaign = repo.configDao.getAllCampaigns().firstOrNull { c ->
            today >= normalizeDate(c.startDate) && today <= normalizeDate(c.endDate)
        }
        if (activeCampaign != null) {
            val scheduleForToday = repo.configDao.getCampaignScheduleForDate(activeCampaign.campaignId, today)
            if (scheduleForToday != null) {
                val interval = repo.configDao.getIntervalForTime(scheduleForToday.scheduleId, now)
                if (interval != null) {
                    Log.d(TAG, "Level 2: campaign schedule playlist=${interval.playlistId} (campaign=${activeCampaign.campaignId})")
                    return ResolvedContent.PlaylistContent(
                        interval.playlistId,
                        ResolvedContent.PlaylistContent.Source.CAMPAIGN_SCHEDULE
                    )
                }
            }
        }

        // Level 3: Default group schedule
        groupConfig.scheduleId?.let { scheduleId ->
            val interval = repo.configDao.getIntervalForTime(scheduleId, now)
            if (interval != null) {
                Log.d(TAG, "Level 3: default group schedule playlist=${interval.playlistId}")
                return ResolvedContent.PlaylistContent(
                    interval.playlistId,
                    ResolvedContent.PlaylistContent.Source.DEFAULT_SCHEDULE
                )
            }
        }

        // Level 4: Default playlist. A playlist may contain virtual SSP slots
        // (media_type 'ssp'/'idle'), which use the group's connectivity via
        // PlaylistBuilder — so a configured playlist takes precedence over the
        // pure group-SSP mode below.
        groupConfig.playlistId?.let { playlistId ->
            Log.d(TAG, "Level 4: default playlist=$playlistId")
            return ResolvedContent.PlaylistContent(
                playlistId,
                ResolvedContent.PlaylistContent.Source.DEFAULT_PLAYLIST
            )
        }

        // Level 5: Group SSP (pure programmatic mode — only when no default playlist)
        groupConfig.sspConnectivityId?.let { sspId ->
            val conn = repo.configDao.getSspConnectivity(sspId)
            if (conn != null) {
                Log.d(TAG, "Level 5: group SSP connectivity=${conn.id}")
                return ResolvedContent.GroupSsp(conn)
            }
        }

        // Level 6: Logo
        Log.d(TAG, "Level 6: logo fallback")
        return ResolvedContent.Logo
    }

    /** Normalizes a date string (ISO or yyyy-MM-dd) to yyyy-MM-dd for safe lexicographic comparison. */
    private fun normalizeDate(raw: String): String = raw.take(10)
}
