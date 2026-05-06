package com.example.signage_front.receiver

import android.app.KeyguardManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.PowerManager
import android.util.Log
import com.example.signage_front.MainActivity

class WakeReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        Log.d("WakeReceiver", "--- WAKE SIGNAL RECEIVED ---")
        
        val powerManager = context.getSystemService(Context.POWER_SERVICE) as PowerManager
        val keyguardManager = context.getSystemService(Context.KEYGUARD_SERVICE) as KeyguardManager
        
        // 1. Force Screen On
        // SCREEN_BRIGHT_WAKE_LOCK is specifically for turning the screen on.
        @Suppress("DEPRECATION")
        val wakeLock = powerManager.newWakeLock(
            PowerManager.SCREEN_BRIGHT_WAKE_LOCK or
            PowerManager.ACQUIRE_CAUSES_WAKEUP or
            PowerManager.ON_AFTER_RELEASE, "SignageApp:WakeLock"
        )
        
        Log.d("WakeReceiver", "Acquiring WakeLock and turning screen on...")
        wakeLock.acquire(10000L) // Hold for 10 seconds to allow Activity to load

        // 2. Explicitly disable the keyguard
        try {
            @Suppress("DEPRECATION")
            val keyguardLock = keyguardManager.newKeyguardLock("SignageApp:KeyguardLock")
            keyguardLock.disableKeyguard()
            Log.d("WakeReceiver", "Keyguard disabled.")
        } catch (e: Exception) {
            Log.e("WakeReceiver", "Failed to disable keyguard", e)
        }

        // 3. Launch or Resume MainActivity
        Log.d("WakeReceiver", "Starting MainActivity...")
        val activityIntent = Intent(context, MainActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            addFlags(Intent.FLAG_ACTIVITY_REORDER_TO_FRONT)
            addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP)
        }
        context.startActivity(activityIntent)
    }
}
