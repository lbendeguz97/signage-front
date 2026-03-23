package com.example.signage_front.ui.screens

import androidx.annotation.OptIn
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView
import com.multiplatform.webview.web.WebView
import com.multiplatform.webview.web.rememberWebViewState

sealed class AdContent {
    data class Html(val url: String) : AdContent()
    data class Video(val url: String) : AdContent()
}

@Composable
fun AdScreen(
    content: AdContent = AdContent.Html("https://www.google.com"),
    modifier: Modifier = Modifier
) {
    Box(modifier = modifier.fillMaxSize()) {
        when (content) {
            is AdContent.Html -> HtmlContent(url = content.url)
            is AdContent.Video -> VideoContent(videoUrl = content.url)
        }
    }
}

@Composable
fun HtmlContent(url: String, modifier: Modifier = Modifier) {
    val webViewState = rememberWebViewState(url = url)
    WebView(
        state = webViewState,
        modifier = modifier.fillMaxSize(),
        onCreated = { webView ->
            webView.settings.javaScriptEnabled = true
            webView.settings.domStorageEnabled = true
        }
    )
}

@OptIn(UnstableApi::class)
@Composable
fun VideoContent(videoUrl: String, modifier: Modifier = Modifier) {
    val context = LocalContext.current

    val exoPlayer = remember {
        ExoPlayer.Builder(context).build().apply {
            val mediaItem = MediaItem.fromUri(videoUrl)
            setMediaItem(mediaItem)
            prepare()
            playWhenReady = true
            repeatMode = Player.REPEAT_MODE_ALL
        }
    }

    DisposableEffect(Unit) {
        onDispose {
            exoPlayer.release()
        }
    }

    AndroidView(
        factory = { ctx ->
            PlayerView(ctx).apply {
                player = exoPlayer
                useController = false // No buttons visible
                resizeMode = androidx.media3.ui.AspectRatioFrameLayout.RESIZE_MODE_ZOOM
            }
        },
        modifier = modifier.fillMaxSize()
    )
}
