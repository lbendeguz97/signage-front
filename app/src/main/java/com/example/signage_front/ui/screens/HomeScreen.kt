package com.example.signage_front.ui.screens

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.example.signage_front.R
import com.example.signage_front.network.AdScheduler
import com.example.signage_front.ui.theme.SignagefrontTheme
import kotlinx.coroutines.delay

/**
 * Splash/home screen: shows the logo while the first sync runs and hands off to
 * the ad screen as soon as content is ready (or after a 60s safety timeout).
 */
@Composable
fun HomeScreen(modifier: Modifier = Modifier, onNavigateToAd: () -> Unit) {
    val syncReady by AdScheduler.syncReady.collectAsState()
    var navigated by remember { mutableStateOf(false) }

    // Hand off as soon as ads/pages/config are all synced.
    LaunchedEffect(syncReady) {
        if (syncReady && !navigated) {
            navigated = true
            onNavigateToAd()
        }
    }

    // Safety timeout: never linger on the splash for more than 60s.
    LaunchedEffect(Unit) {
        delay(60_000L)
        if (!navigated) {
            navigated = true
            onNavigateToAd()
        }
    }

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(Color.Black),
        contentAlignment = Alignment.Center
    ) {
        Image(
            painter = painterResource(id = R.drawable.ic_aura_logo),
            contentDescription = "Aura",
            modifier = Modifier.width(280.dp)
        )
    }
}

@Preview(showBackground = true)
@Composable
fun HomeScreenPreview() {
    SignagefrontTheme {
        HomeScreen(onNavigateToAd = {})
    }
}
