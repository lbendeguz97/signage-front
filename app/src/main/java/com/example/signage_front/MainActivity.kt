package com.example.signage_front

import android.app.AlarmManager
import android.app.KeyguardManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.os.Bundle
import android.os.SystemClock
import android.util.Log
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Scaffold
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.example.signage_front.network.AdScheduler
import com.example.signage_front.network.Config
import com.example.signage_front.network.NetworkClientProvider
import com.example.signage_front.network.SecurityManager
import com.example.signage_front.receiver.WakeReceiver
import com.example.signage_front.ui.screens.AdContent
import com.example.signage_front.ui.screens.AdScreen
import com.example.signage_front.ui.screens.EnrollmentScreen
import com.example.signage_front.ui.screens.HomeScreen
import com.example.signage_front.ui.theme.SignagefrontTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject

class MainActivity : ComponentActivity() {
    private val TAG = "SignageAuth"

    private val screenOffReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action == Intent.ACTION_SCREEN_OFF) {
                Log.d(TAG, "Screen off detected. Scheduling wake-up in 1 minute.")
                scheduleWakeUp(context)
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        // Apply kiosk flags immediately
        configureKioskWindow()

        registerReceiver(screenOffReceiver, IntentFilter(Intent.ACTION_SCREEN_OFF))

        setContent {
            val navController = rememberNavController()
            var isEnrolled by remember { mutableStateOf(SecurityManager.hasValidKey()) }
            var isCheckingIn by remember { mutableStateOf(false) }
            var enrollmentError by remember { mutableStateOf<String?>(null) }
            val scope = rememberCoroutineScope()

            LaunchedEffect(Unit) {
                if (isEnrolled) {
                    checkIn(
                        onSuccess = { 
                            Log.d(TAG, "Background check-in successful")
                            AdScheduler.startPolling(applicationContext)
                        },
                        onFailure = { 
                            Log.w(TAG, "Background check-in failed, navigating to enrollment")
                            navController.navigate("enrollment")
                        }
                    )
                } else {
                    navController.navigate("enrollment") {
                        popUpTo("home") { inclusive = false }
                    }
                }
            }

            SignagefrontTheme {
                Scaffold(modifier = Modifier.fillMaxSize()) { innerPadding ->
                    NavHost(
                        navController = navController,
                        startDestination = "home",
                        modifier = Modifier.padding(innerPadding)
                    ) {
                        composable("home") {
                            HomeScreen(onNavigateToAd = { navController.navigate("ad") })
                        }
                        composable("enrollment") {
                            EnrollmentScreen(
                                isLoading = isCheckingIn,
                                errorMessage = enrollmentError,
                                onEnroll = { otp ->
                                    scope.launch {
                                        isCheckingIn = true
                                        enrollmentError = null
                                        val success = enroll(otp)
                                        if (success) {
                                            checkIn(
                                                onSuccess = { 
                                                    isEnrolled = true
                                                    AdScheduler.startPolling(applicationContext)
                                                    navController.navigate("home") { 
                                                        popUpTo("enrollment") { inclusive = true } 
                                                    } 
                                                },
                                                onFailure = { enrollmentError = "Check-in failed after enrollment." }
                                            )
                                        } else {
                                            enrollmentError = "Enrollment failed. Please check your OTP."
                                        }
                                        isCheckingIn = false
                                    }
                                }
                            )
                        }
                        composable("ad") {
                            AdScreen(content = AdContent.Html("https://www.google.com"))
                        }
                    }
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        Log.d(TAG, "onNewIntent received - Re-applying kiosk flags")
        configureKioskWindow()
    }

    private fun configureKioskWindow() {
        // Force the screen to turn on and stay on, bypassing the keyguard
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
            setShowWhenLocked(true)
            setTurnScreenOn(true)
            val keyguardManager = getSystemService(Context.KEYGUARD_SERVICE) as KeyguardManager
            keyguardManager.requestDismissKeyguard(this, null)
        } else {
            @Suppress("DEPRECATION")
            window.addFlags(
                WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or
                        WindowManager.LayoutParams.FLAG_DISMISS_KEYGUARD or
                        WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON or
                        WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON
            )
        }
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
    }

    private fun scheduleWakeUp(context: Context) {
        val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        val intent = Intent(context, WakeReceiver::class.java)
        
        val flags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        } else {
            PendingIntent.FLAG_UPDATE_CURRENT
        }

        val pendingIntent = PendingIntent.getBroadcast(context, 0, intent, flags)
        val triggerAt = SystemClock.elapsedRealtime() + 60_000
        
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            alarmManager.setExactAndAllowWhileIdle(AlarmManager.ELAPSED_REALTIME_WAKEUP, triggerAt, pendingIntent)
        } else {
            alarmManager.setExact(AlarmManager.ELAPSED_REALTIME_WAKEUP, triggerAt, pendingIntent)
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        try { unregisterReceiver(screenOffReceiver) } catch (e: Exception) {}
    }

    private suspend fun checkIn(onSuccess: () -> Unit, onFailure: () -> Unit) {
        withContext(Dispatchers.IO) {
            if (!SecurityManager.hasValidKey()) {
                withContext(Dispatchers.Main) { onFailure() }
                return@withContext
            }
            try {
                val client = NetworkClientProvider.getMTlsClient(applicationContext)
                val request = Request.Builder().url("${Config.BASE_URL}/checkin").get().build()
                client.newCall(request).execute().use { response ->
                    if (response.code == 204) withContext(Dispatchers.Main) { onSuccess() }
                    else withContext(Dispatchers.Main) { onFailure() }
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) { onFailure() }
            }
        }
    }

    private suspend fun enroll(otp: String): Boolean {
        return withContext(Dispatchers.IO) {
            try {
                SecurityManager.generateKeyPair()
                val csr = SecurityManager.createCsr()
                val client = NetworkClientProvider.getStandardClient()
                val json = JSONObject().apply { put("otp", otp); put("csr", csr) }
                val body = json.toString().toRequestBody("application/json".toMediaType())
                val request = Request.Builder().url("${Config.BASE_URL}/enroll").post(body).build()
                client.newCall(request).execute().use { response ->
                    if (response.isSuccessful) {
                        val certPem = response.body?.string() ?: return@withContext false
                        SecurityManager.saveCertificate(certPem)
                        true
                    } else false
                }
            } catch (e: Exception) { false }
        }
    }
}
