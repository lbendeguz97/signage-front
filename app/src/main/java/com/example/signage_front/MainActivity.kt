package com.example.signage_front

import android.os.Bundle
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Scaffold
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
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
            var isLoading by remember { mutableStateOf(true) }
            var enrollmentError by remember { mutableStateOf<String?>(null) }
            val scope = rememberCoroutineScope()

            SignagefrontTheme {
                Scaffold(modifier = Modifier.fillMaxSize()) { innerPadding ->
                    NavHost(
                        navController = navController,
                        startDestination = "splash",
                        modifier = Modifier.padding(innerPadding)
                    ) {
                        composable("splash") {
                            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                                CircularProgressIndicator()
                            }
                            LaunchedEffect(Unit) {
                                checkIn(
                                    onSuccess = { navController.navigate("home") { popUpTo("splash") { inclusive = true } } },
                                    onFailure = { navController.navigate("enrollment") { popUpTo("splash") { inclusive = true } } }
                                )
                                isLoading = false
                            }
                        }
                        composable("enrollment") {
                            EnrollmentScreen(
                                isLoading = isLoading,
                                errorMessage = enrollmentError,
                                onEnroll = { otp ->
                                    scope.launch {
                                        isLoading = true
                                        enrollmentError = null
                                        val success = enroll(otp)
                                        if (success) {
                                            checkIn(
                                                onSuccess = { navController.navigate("home") { popUpTo("enrollment") { inclusive = true } } },
                                                onFailure = { enrollmentError = "Check-in failed after enrollment." }
                                            )
                                        } else {
                                            enrollmentError = "Enrollment failed. Please check your OTP."
                                        }
                                        isLoading = false
                                    }
                                }
                            )
                        }
                        composable("home") {
                            HomeScreen(onNavigateToAd = { navController.navigate("ad") })
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
            if (!SecurityManager.hasValidKey()) {
                withContext(Dispatchers.Main) { onFailure() }
                return@withContext
            }

            try {
                // Pass application context to get the raw resource
                val client = NetworkClientProvider.getMTlsClient(applicationContext)
                val request = Request.Builder()
                    .url("${Config.BASE_URL}/checkin")
                    .get()
                    .build()

                client.newCall(request).execute().use { response ->
                    when (response.code) {
                        204 -> {
                            // Start polling on successful check-in
                            AdScheduler.startPolling(applicationContext)
                            withContext(Dispatchers.Main) { onSuccess() }
                        }
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
            try {
                SecurityManager.generateKeyPair()
                val csr = SecurityManager.createCsr()

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

                client.newCall(request).execute().use { response ->
                    if (response.isSuccessful) {
                        val responseBody = response.body?.string() ?: return@withContext false
                        val jsonResponse = JSONObject(responseBody)
                        val certPem = jsonResponse.getString("certificate")
                        
                        // Load Root CA from resources to append to the chain
                        val rootCa = resources.openRawResource(R.raw.ca_cert).bufferedReader().use { it.readText() }
                        
                        SecurityManager.saveCertificate(certPem, rootCa)
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
