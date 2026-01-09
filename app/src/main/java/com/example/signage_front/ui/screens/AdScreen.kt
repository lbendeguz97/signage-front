package com.example.signage_front.ui.screens

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.example.signage_front.ui.theme.SignagefrontTheme
import dev.datlag.web.WebView
import dev.datlag.web.rememberWebViewState

@Composable
fun AdScreen(modifier: Modifier = Modifier) {
    Box(modifier = modifier.fillMaxSize().padding(16.dp)) {
        // We can add logic here to switch between video and html ads.
        // For now, we will just display a sample HTML ad from a URL.
        HtmlContent(url = "https://www.wikipedia.org/")
    }
}

@Composable
fun HtmlContent(url: String, modifier: Modifier = Modifier) {
    val webViewState = rememberWebViewState(url = url)
    WebView(
        state = webViewState,
        modifier = modifier.fillMaxSize(),
        onCreated = {
            it.settings.javaScriptEnabled = true
            it.settings.domStorageEnabled = true
        }
    )
}

// @Composable
// fun VideoContent(modifier: Modifier = Modifier) {
//     // For video, we would use ExoPlayer here, wrapped in an AndroidView.
//     // This requires adding the ExoPlayer dependency.
// }

@Preview(showBackground = true)
@Composable
fun AdScreenPreview() {
    SignagefrontTheme {
        AdScreen()
    }
}
