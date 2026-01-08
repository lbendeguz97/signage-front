package com.example.signage_front.ui.screens

import android.view.ViewGroup
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.example.signage_front.ui.theme.SignagefrontTheme

@Composable
fun AdScreen(modifier: Modifier = Modifier) {
    Box(modifier = modifier.fillMaxSize().padding(16.dp)) {
        // We can add logic here to switch between video and html ads.
        // For now, we will just display a sample HTML ad from a URL.
        HtmlContent(url = "https://www.vanenet.hu")
    }
}

@Composable
fun HtmlContent(url: String, modifier: Modifier = Modifier) {
    AndroidView(
        modifier = modifier.fillMaxSize(),
        factory = {
            WebView(it).apply {
                layoutParams = ViewGroup.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT
                )
                webViewClient = WebViewClient()
                settings.javaScriptEnabled = true
                loadUrl(url)
            }
        },
        update = {
            it.loadUrl(url)
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
