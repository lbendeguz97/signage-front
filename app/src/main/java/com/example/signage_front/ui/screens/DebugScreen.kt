package com.example.signage_front.ui.screens

import android.content.Context
import android.net.ConnectivityManager
import android.net.wifi.WifiManager
import android.os.Build
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.example.signage_front.network.Config
import java.net.NetworkInterface
import java.util.*

@Composable
fun DebugScreen(
    onRetry: () -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val networkInfo = remember { getNetworkInfo(context) }

    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(16.dp)
            .verticalScroll(rememberScrollState())
    ) {
        Text(
            text = "Network Debug Information",
            style = MaterialTheme.typography.headlineMedium,
            color = MaterialTheme.colorScheme.error
        )
        Spacer(modifier = Modifier.height(24.dp))

        DebugItem("Environment", Config.ENV.uppercase())
        DebugItem("Primary Backend", Config.BASE_URL)
        DebugItem("Backup Backend", Config.BASE_URL_BACKUP)
        DebugItem("Last Attempted URL", Config.currentBaseUrl)
        
        Spacer(modifier = Modifier.height(16.dp))
        
        DebugItem("Connection Status", if (networkInfo.isConnected) "Connected" else "Disconnected")
        DebugItem("Network Type", networkInfo.type)
        DebugItem("WiFi SSID", networkInfo.ssid ?: "N/A")
        DebugItem("Mobile Data", if (networkInfo.isMobile) "Enabled" else "Disabled/Unavailable")
        DebugItem("Local IP Address", networkInfo.ipAddress ?: "N/A")
        
        Spacer(modifier = Modifier.height(16.dp))
        
        DebugItem("Android Version", "${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})")
        DebugItem("Device", "${Build.MANUFACTURER} ${Build.MODEL}")
        DebugItem("Serial (Pseudo)", Build.SERIAL)
        DebugItem("Timestamp", Date().toString())

        Spacer(modifier = Modifier.height(32.dp))

        Button(
            onClick = onRetry,
            modifier = Modifier.fillMaxWidth(),
            colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary)
        ) {
            Text("Retry Connection")
        }
    }
}

@Composable
fun DebugItem(label: String, value: String) {
    Column(modifier = Modifier.padding(vertical = 8.dp)) {
        Text(text = label, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.secondary)
        Text(text = value, style = MaterialTheme.typography.bodyLarge)
        HorizontalDivider(modifier = Modifier.padding(top = 8.dp), thickness = 0.5.dp, color = MaterialTheme.colorScheme.outlineVariant)
    }
}

data class NetworkDetails(
    val isConnected: Boolean,
    val type: String,
    val isMobile: Boolean,
    val ssid: String?,
    val ipAddress: String?
)

fun getNetworkInfo(context: Context): NetworkDetails {
    val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
    @Suppress("DEPRECATION")
    val activeNetwork = cm.activeNetworkInfo
    @Suppress("DEPRECATION")
    val isConnected = activeNetwork?.isConnectedOrConnecting == true
    @Suppress("DEPRECATION")
    val type = activeNetwork?.typeName ?: "Unknown"
    @Suppress("DEPRECATION")
    val isMobile = activeNetwork?.type == ConnectivityManager.TYPE_MOBILE

    var ssid: String? = null
    @Suppress("DEPRECATION")
    if (activeNetwork?.type == ConnectivityManager.TYPE_WIFI) {
        val wm = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
        val info = wm.connectionInfo
        ssid = info.ssid?.removeSurrounding("\"")
        if (ssid == "<unknown ssid>") ssid = null
    }

    return NetworkDetails(
        isConnected = isConnected,
        type = type,
        isMobile = isMobile,
        ssid = ssid,
        ipAddress = getIPAddress()
    )
}

fun getIPAddress(): String? {
    try {
        val interfaces = NetworkInterface.getNetworkInterfaces()
        while (interfaces.hasMoreElements()) {
            val networkInterface = interfaces.nextElement()
            val addresses = networkInterface.inetAddresses
            while (addresses.hasMoreElements()) {
                val address = addresses.nextElement()
                if (!address.isLoopbackAddress && address is java.net.Inet4Address) {
                    return address.hostAddress
                }
            }
        }
    } catch (e: Exception) {
        e.printStackTrace()
    }
    return null
}
