package com.example.signage_front

import android.os.Bundle
import android.util.Log
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

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        setContent {
            val navController = rememberNavController()
            var isEnrolled by remember { mutableStateOf(SecurityManager.hasValidKey()) }
            var isCheckingIn by remember { mutableStateOf(false) }
            var enrollmentError by remember { mutableStateOf<String?>(null) }
            val scope = rememberCoroutineScope()

            LaunchedEffect(Unit) {
                Log.d(TAG, "App started. checking enrollment status: $isEnrolled")
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
                    Log.d(TAG, "Not enrolled, navigating to enrollment screen")
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

    private suspend fun checkIn(onSuccess: () -> Unit, onFailure: () -> Unit) {
        withContext(Dispatchers.IO) {
            Log.d(TAG, "Executing check-in...")
            if (!SecurityManager.hasValidKey()) {
                Log.w(TAG, "Check-in aborted: No valid key found")
                withContext(Dispatchers.Main) { onFailure() }
                return@withContext
            }

            try {
                val client = NetworkClientProvider.getMTlsClient(applicationContext)
                val request = Request.Builder()
                    .url("${Config.BASE_URL}/checkin")
                    .get()
                    .build()

                Log.d(TAG, "Sending GET to ${request.url}")
                client.newCall(request).execute().use { response ->
                    Log.d(TAG, "Response code: ${response.code}")
                    when (response.code) {
                        204 -> withContext(Dispatchers.Main) { onSuccess() }
                        401, 403 -> {
                            Log.w(TAG, "Check-in unauthorized: ${response.code}")
                            withContext(Dispatchers.Main) { onFailure() }
                        }
                        else -> {
                            Log.e(TAG, "Check-in failed with code: ${response.code}")
                            withContext(Dispatchers.Main) { onFailure() }
                        }
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "mTLS Handshake or Network error during check-in", e)
                withContext(Dispatchers.Main) { onFailure() }
            }
        }
    }

    private suspend fun enroll(otp: String): Boolean {
        return withContext(Dispatchers.IO) {
            Log.d(TAG, "Starting enrollment with OTP: $otp")
            try {
                SecurityManager.generateKeyPair()
                val csr = SecurityManager.createCsr()
                Log.d(TAG, "CSR generated successfully")

                val client = NetworkClientProvider.getStandardClient()
                val json = JSONObject().apply {
                    put("otp", otp)
                    put("csr", csr)
                }
                
                val body = json.toString().toRequestBody("application/json".toMediaType())
                val request = Request.Builder()
                    .url("${Config.BASE_URL}/enroll")
                    .post(body)
                    .build()

                Log.d(TAG, "Sending POST to ${request.url}")
                client.newCall(request).execute().use { response ->
                    Log.d(TAG, "Enrollment response code: ${response.code}")
                    if (response.isSuccessful) {
                        val certPem = response.body?.string() ?: return@withContext false
                        SecurityManager.saveCertificate(certPem)
                        Log.d(TAG, "Certificate saved successfully")
                        true
                    } else {
                        Log.e(TAG, "Enrollment failed: ${response.code} - ${response.message}")
                        false
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error during enrollment process", e)
                false
            }
        }
    }
}
