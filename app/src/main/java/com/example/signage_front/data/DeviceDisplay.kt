package com.example.signage_front.data

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Device-level display preferences and the transient "idle" (soft screen-off) state.
 *
 * Brightness is applied at the window level (see MainActivity), so no special
 * permission is required and it only affects this kiosk app. Idle is intentionally
 * not persisted: after a reboot/process restart the device returns to ad display.
 */
object DeviceDisplay {
    private const val PREFS = "ui_prefs"
    private const val KEY_BRIGHTNESS = "ui_brightness"

    /** Default screen brightness (0f..1f). */
    const val DEFAULT_BRIGHTNESS = 0.8f

    /** Screen brightness while idling — dim, but still readable as a blank panel. */
    const val IDLE_BRIGHTNESS = 0.04f

    /** How long the device stays idle before automatically waking to the ad loop. */
    const val IDLE_TIMEOUT_MS = 3 * 60 * 1000L

    private val _brightness = MutableStateFlow(DEFAULT_BRIGHTNESS)
    val brightness: StateFlow<Float> = _brightness.asStateFlow()

    private val _isIdle = MutableStateFlow(false)
    val isIdle: StateFlow<Boolean> = _isIdle.asStateFlow()

    fun init(context: Context) {
        val prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        _brightness.value = prefs.getFloat(KEY_BRIGHTNESS, DEFAULT_BRIGHTNESS)
            .coerceIn(0.05f, 1f)
    }

    fun setBrightness(context: Context, value: Float) {
        val clamped = value.coerceIn(0.05f, 1f)
        _brightness.value = clamped
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putFloat(KEY_BRIGHTNESS, clamped).apply()
    }

    fun enterIdle() {
        _isIdle.value = true
    }

    fun exitIdle() {
        _isIdle.value = false
    }
}
