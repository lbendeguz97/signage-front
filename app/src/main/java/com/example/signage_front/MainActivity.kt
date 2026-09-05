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
import android.view.View
import android.view.WindowInsets
import android.view.WindowInsetsController
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.example.signage_front.data.AdRepository
import com.example.signage_front.data.AdStatus
import com.example.signage_front.data.ContentOrchestrator
import com.example.signage_front.data.GroupConfig
import com.example.signage_front.data.PlaylistItem
import com.example.signage_front.data.PlaylistBuilder
import com.example.signage_front.data.ResolvedContent
import com.example.signage_front.network.AdScheduler
import com.example.signage_front.network.Config
import com.example.signage_front.network.MediaManager
import com.example.signage_front.network.NetworkClientProvider
import com.example.signage_front.network.SecurityManager
import com.example.signage_front.receiver.WakeReceiver
import com.example.signage_front.ui.screens.*
import com.example.signage_front.ui.theme.SignagefrontTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.io.File
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import android.content.RestrictionsManager
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec
import java.nio.ByteBuffer
import org.bouncycastle.util.encoders.Base32

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

        configureKioskWindow()
        registerReceiver(screenOffReceiver, IntentFilter(Intent.ACTION_SCREEN_OFF))

        val repository = AdRepository(applicationContext)

        ContentOrchestrator.start(applicationContext)

        setContent {
            val navController = rememberNavController()
            var isEnrolled by remember { mutableStateOf(SecurityManager.hasValidKey()) }
            var isCheckingIn by remember { mutableStateOf(false) }
            var enrollmentError by remember { mutableStateOf<String?>(null) }
            val scope = rememberCoroutineScope()
            
            val ads by repository.getAllAds().collectAsState(initial = emptyList())

            LaunchedEffect(Unit) {
                if (!checkServerAvailability()) {
                    if (Config.ENV == "dev") {
                        navController.navigate("debug") {
                            popUpTo("home") { inclusive = false }
                        }
                    } else {
                        Log.e(TAG, "Production environment: Server unavailable. Retrying in background...")
                    }
                    return@LaunchedEffect
                }

                if (isEnrolled) {
                    checkIn(
                        onSuccess = { 
                            Log.d(TAG, "Background check-in successful")
                            AdScheduler.startPolling(applicationContext)
                            scope.launch { AdScheduler.fetchAndSyncAdStatus(applicationContext) }
                        },
                        onFailure = { 
                            Log.w(TAG, "Background check-in failed, navigating to enrollment")
                            navController.navigate("enrollment")
                        }
                    )
                } else {
                    val regKey = getRegistrationKey(applicationContext)
                    if (!regKey.isNullOrEmpty()) {
                        Log.i(TAG, "Found registration key, navigating to enrollment and attempting automatic enrollment...")
                        navController.navigate("enrollment") {
                            popUpTo("home") { inclusive = false }
                        }
                        isCheckingIn = true
                        enrollmentError = null
                        val success = enroll(regKey)
                        if (success) {
                            checkIn(
                                onSuccess = { 
                                    isEnrolled = true
                                    AdScheduler.startPolling(applicationContext)
                                    scope.launch { AdScheduler.fetchAndSyncAdStatus(applicationContext) }
                                    navController.navigate("home") { 
                                        popUpTo("enrollment") { inclusive = true } 
                                    } 
                                },
                                onFailure = { 
                                    enrollmentError = "Check-in failed after automatic enrollment. Please verify server logs."
                                }
                            )
                        } else {
                            enrollmentError = "Automatic enrollment failed. Please check your registration key."
                        }
                        isCheckingIn = false
                    } else {
                        navController.navigate("enrollment") {
                            popUpTo("home") { inclusive = false }
                        }
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
                                        if (!checkServerAvailability()) {
                                            if (Config.ENV == "dev") {
                                                navController.navigate("debug")
                                            } else {
                                                enrollmentError = "Server unavailable. Please check connection."
                                            }
                                            isCheckingIn = false
                                            return@launch
                                        }
                                        val success = enroll(otp)
                                        if (success) {
                                            checkIn(
                                                onSuccess = { 
                                                    isEnrolled = true
                                                    AdScheduler.startPolling(applicationContext)
                                                    scope.launch { AdScheduler.fetchAndSyncAdStatus(applicationContext) }
                                                    navController.navigate("home") { 
                                                        popUpTo("enrollment") { inclusive = true } 
                                                    } 
                                                },
                                                onFailure = { 
                                                    enrollmentError = "Check-in failed after enrollment. Please verify server logs."
                                                }
                                            )
                                        } else {
                                            enrollmentError = "Enrollment failed. Please check your OTP and try again."
                                        }
                                        isCheckingIn = false
                                    }
                                }
                            )
                        }
                        composable("ad") {
                            val verifiedAds = ads.filter { it.adAllowed && it.syncStatus == "VERIFIED" }
                            val groupConfig by repository.configDao.getGroupConfigFlow().collectAsState(initial = null)
                            val orchState by ContentOrchestrator.state.collectAsState()

                            val activeTrigger = orchState.triggerQueue.firstOrNull()
                            val base = orchState.base

                            // Which playlist feeds the current ad list (null for media trigger / group SSP / logo)
                            val activePlaylistId = when {
                                activeTrigger != null && activeTrigger.actionType == "playlist" -> activeTrigger.actionId
                                activeTrigger == null -> (base as? ResolvedContent.PlaylistContent)?.playlistId
                                else -> null
                            }
                            val playlistAds by repository.configDao.getPlaylistAdsFlow(activePlaylistId ?: -1L)
                                .collectAsState(initial = emptyList())

                            // Ordered list of PlaylistItems for the resolved content source (trigger or base).
                            var playlistItems by remember { mutableStateOf<List<PlaylistItem>>(emptyList()) }
                            LaunchedEffect(orchState, playlistAds, verifiedAds, groupConfig?.sspConnectivityId) {
                                val sspConnectivity = groupConfig?.sspConnectivityId?.let {
                                    repository.configDao.getSspConnectivity(it)
                                }
                                val fallbackFile = resolveFallbackFile(applicationContext, groupConfig, verifiedAds)
                                val trigger = orchState.triggerQueue.firstOrNull()
                                playlistItems = when {
                                    trigger != null -> when (trigger.actionType) {
                                        "media" -> verifiedAds.firstOrNull { it.adId == trigger.actionId.toString() }
                                            ?.let { listOf(PlaylistItem.Standard(it, null)) } ?: emptyList()
                                        else -> PlaylistBuilder.buildPlaylistItems(playlistAds, verifiedAds, sspConnectivity, fallbackFile)
                                    }
                                    else -> when (val b = orchState.base) {
                                        is ResolvedContent.PlaylistContent ->
                                            PlaylistBuilder.buildPlaylistItems(playlistAds, verifiedAds, sspConnectivity, fallbackFile)
                                        is ResolvedContent.GroupSsp -> listOf(PlaylistItem.GroupSspSlot(b.connectivity, fallbackFile))
                                        ResolvedContent.Logo -> listOf(PlaylistItem.Logo)
                                    }
                                }
                            }

                            LaunchedEffect(playlistItems.isEmpty()) {
                                if (playlistItems.isEmpty() && isEnrolled) {
                                    Log.d(TAG, "No playable items found, triggering on-demand sync.")
                                    AdScheduler.fetchAndSyncAdStatus(applicationContext)
                                }
                            }

                            if (playlistItems.isNotEmpty()) {
                                AdScreen(
                                    items = playlistItems,
                                    pendingInterruptPriority = orchState.pendingInterrupt?.priority,
                                    onTriggerLoopComplete = {
                                        val t = orchState.triggerQueue.firstOrNull()
                                        if (t != null) ContentOrchestrator.onTriggerComplete(t.triggerId)
                                    },
                                    onAdClick = { redirectUrl ->
                                        val fullQrUrl = Config.REDIRECT_ROOT.toHttpUrlOrNull()?.newBuilder()
                                            ?.addQueryParameter("url", redirectUrl)
                                            ?.build()
                                            ?.toString() ?: redirectUrl
                                        
                                        val encodedUrl = URLEncoder.encode(fullQrUrl, StandardCharsets.UTF_8.toString())
                                        navController.navigate("qrcode/$encodedUrl")
                                    },
                                    onBackToHome = {
                                        navController.navigate("home") {
                                            popUpTo("home") { inclusive = true }
                                        }
                                    },
                                    onNavigateToDebug = {
                                        navController.navigate("debug")
                                    }
                                )
                            } else {
                                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                        CircularProgressIndicator()
                                        Text(
                                            text = "Preparing content...", 
                                            modifier = Modifier.padding(top = 16.dp)
                                        )
                                    }
                                }
                            }
                        }
                        composable(
                            route = "qrcode/{url}",
                            arguments = listOf(navArgument("url") { type = NavType.StringType })
                        ) { backStackEntry ->
                            val url = backStackEntry.arguments?.getString("url") ?: ""
                            QrCodeScreen(url = url)
                        }
                        composable("debug") {
                            DebugScreen(onRetry = {
                                scope.launch {
                                    if (checkServerAvailability()) {
                                        if (SecurityManager.hasValidKey()) {
                                            checkIn(
                                                onSuccess = { 
                                                    navController.navigate("home") { popUpTo("debug") { inclusive = true } }
                                                    AdScheduler.startPolling(applicationContext)
                                                },
                                                onFailure = { navController.navigate("enrollment") { popUpTo("debug") { inclusive = true } } }
                                            )
                                        } else {
                                            navController.navigate("enrollment") { popUpTo("debug") { inclusive = true } }
                                        }
                                    }
                                }
                            })
                        }
                    }
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        configureKioskWindow()
    }

    private fun configureKioskWindow() {
        // Keep screen on
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        
        // Show when locked and turn screen on
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
                        WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON
            )
        }
        
        // Enable immersive fullscreen mode
        enableImmersiveFullscreen()
    }
    
    private fun enableImmersiveFullscreen() {
        // Make content draw behind system bars
        WindowCompat.setDecorFitsSystemWindows(window, false)
        
        val windowInsetsController = WindowCompat.getInsetsController(window, window.decorView)
        
        // Hide both status bar and navigation bar
        windowInsetsController.hide(WindowInsetsCompat.Type.systemBars())
        
        // Use sticky immersive mode - bars will temporarily appear on swipe but auto-hide
        windowInsetsController.systemBarsBehavior = 
            WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
    }
    
    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) {
            // Re-apply immersive mode when window regains focus
            enableImmersiveFullscreen()
        }
    }
    
    override fun onResume() {
        super.onResume()
        // Re-apply immersive mode when activity resumes
        enableImmersiveFullscreen()
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
                Log.e(TAG, "Check-in aborted: No valid key found.")
                withContext(Dispatchers.Main) { onFailure() }
                return@withContext
            }
            try {
                Log.d(TAG, "Attempting mTLS check-in...")
                val client = NetworkClientProvider.getMTlsClient(applicationContext)
                val request = Request.Builder().url("${Config.currentBaseUrl}/checkin").get().build()
                client.newCall(request).execute().use { response ->
                    Log.d(TAG, "Check-in response code: ${response.code}")
                    if (response.code == 204) {
                        withContext(Dispatchers.Main) { onSuccess() }
                    } else {
                        Log.e(TAG, "Check-in failed with response: ${response.code}")
                        withContext(Dispatchers.Main) { onFailure() }
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Exception during check-in: ${e.message}", e)
                withContext(Dispatchers.Main) { onFailure() }
            }
        }
    }

    private suspend fun checkServerAvailability(): Boolean {
        return withContext(Dispatchers.IO) {
            if (tryEcho(Config.BASE_URL)) {
                Config.currentBaseUrl = Config.BASE_URL
                return@withContext true
            }
            if (tryEcho(Config.BASE_URL_BACKUP)) {
                Config.currentBaseUrl = Config.BASE_URL_BACKUP
                return@withContext true
            }
            false
        }
    }

    private suspend fun tryEcho(url: String): Boolean {
        return try {
            val client = NetworkClientProvider.getStandardClient(applicationContext)
            val request = Request.Builder().url("$url/echo").get().build()
            client.newCall(request).execute().use { response -> response.code == 204 }
        } catch (e: Exception) {
            Log.e(TAG, "Echo failed for $url: ${e.message}")
            false
        }
    }

    private suspend fun enroll(otp: String): Boolean {
        return withContext(Dispatchers.IO) {
            try {
                Log.d(TAG, "Starting enrollment process...")
                SecurityManager.generateKeyPair()
                val csr = SecurityManager.createCsr(applicationContext)
                val client = NetworkClientProvider.getStandardClient(applicationContext)
                val json = JSONObject().apply { put("otp", otp); put("csr", csr) }
                val body = json.toString().toRequestBody("application/json".toMediaType())
                val request = Request.Builder().url("${Config.currentBaseUrl}/enroll").post(body).build()
                client.newCall(request).execute().use { response ->
                    Log.d(TAG, "Enrollment request sent. Response code: ${response.code}")
                    val responseBody = response.body?.string()
                    if (response.isSuccessful && responseBody != null) {
                        val responseJson = JSONObject(responseBody)
                        val certPem = responseJson.optString("certificate")
                        if (certPem.isNotEmpty()) {
                            Log.d(TAG, "Certificate received, saving...")
                            SecurityManager.saveCertificate(certPem)
                            Log.d(TAG, "Enrollment successful")
                            true
                        } else {
                            Log.e(TAG, "Enrollment successful but certificate field is missing or empty")
                            false
                        }
                    } else {
                        Log.e(TAG, "Enrollment failed on server: ${response.code} - $responseBody")
                        false
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error during enrollment: ${e.message}", e)
                false
            }
        }
    }

    private fun getRegistrationKey(context: Context): String? {
        // First check Intent extras (great for ADB testing and direct launch overrides)
        intent?.getStringExtra("registration_key")?.let {
            if (it.isNotEmpty()) return it
        }
        
        // Also check if any intent extra contains a JSON string with registration_key
        intent?.extras?.let { extras ->
            for (key in extras.keySet()) {
                val value = extras.get(key)?.toString() ?: continue
                if (value.trim().startsWith("{")) {
                    try {
                        val json = JSONObject(value)
                        val regKey = json.optString("registration_key")
                        if (!regKey.isNullOrEmpty()) {
                            return regKey
                        }
                    } catch (e: Exception) {}
                }
            }
        }

        try {
            val restrictionsManager = context.getSystemService(Context.RESTRICTIONS_SERVICE) as? RestrictionsManager
                ?: return null
            val appRestrictions = restrictionsManager.applicationRestrictions ?: return null
            
            // 1. Direct check
            val directKey = appRestrictions.getString("registration_key")
            if (!directKey.isNullOrEmpty()) {
                return directKey
            }
            
            // 2. Check for other keys that might contain a JSON string
            for (key in appRestrictions.keySet()) {
                val value = appRestrictions.getString(key) ?: continue
                if (value.trim().startsWith("{")) {
                    try {
                        val json = JSONObject(value)
                        val regKey = json.optString("registration_key")
                        if (!regKey.isNullOrEmpty()) {
                            return regKey
                        }
                    } catch (e: Exception) {
                        // Not valid JSON or parsing failed
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error reading application restrictions: ${e.message}", e)
        }
        return null
    }
}

/**
 * Resolves the group's fallback media (configJson.fallback_media_id) to a local
 * file, used to fill SSP/idle slot time when no programmatic ad is cached.
 */
private fun resolveFallbackFile(
    context: Context,
    groupConfig: GroupConfig?,
    verifiedAds: List<AdStatus>
): File? {
    if (groupConfig == null) return null
    return try {
        val fallbackId = JSONObject(groupConfig.configJson)
            .optString("fallback_media_id", "")
            .takeIf { it.isNotBlank() } ?: return null
        val ad = verifiedAds.firstOrNull { it.adId == fallbackId } ?: return null
        val file = MediaManager.getLocalFile(context, ad)
        if (file.exists()) file else null
    } catch (e: Exception) {
        Log.e("MainActivity", "Failed to resolve fallback media", e)
        null
    }
}
