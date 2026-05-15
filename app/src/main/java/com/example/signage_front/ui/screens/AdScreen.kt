package com.example.signage_front.ui.screens

import android.graphics.BitmapFactory
import androidx.annotation.OptIn
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView
import com.example.signage_front.data.AdStatus
import com.example.signage_front.network.MediaManager
import com.multiplatform.webview.web.WebView
import com.multiplatform.webview.web.rememberWebViewState
import kotlinx.coroutines.delay
import java.io.File

@Composable
fun AdScreen(
    ads: List<AdStatus>,
    onAdClick: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    var currentIndex by remember { mutableIntStateOf(0) }
    val currentAd = if (ads.isNotEmpty()) ads[currentIndex % ads.size] else null

    if (currentAd == null) {
        Box(modifier = modifier.fillMaxSize())
        return
    }

    val context = LocalContext.current
    val localFile = MediaManager.getLocalFile(context, currentAd)

    // Callback for when an ad finishes. 
    // Explicitly typed as () -> Unit to avoid inferred Int return from currentIndex++
    val onAdFinished: () -> Unit = {
        currentIndex += 1
    }

    Box(
        modifier = modifier
            .fillMaxSize()
            .clickable {
                currentAd.url?.let { onAdClick(it) }
            }
    ) {
        // Use currentIndex as part of the key to force re-composition 
        // and restart the media if there's only one ad in the list.
        key(currentIndex, currentAd.adId) {
            when (currentAd.mediaType) {
                "video" -> {
                    VideoContent(
                        videoFile = localFile,
                        onFinished = onAdFinished
                    )
                }
                "image" -> {
                    ImageContent(
                        imageFile = localFile,
                        displayTimeSeconds = currentAd.displayTime ?: 10,
                        onFinished = onAdFinished
                    )
                }
                "html" -> {
                    HtmlContent(
                        url = currentAd.url ?: "",
                        displayTimeSeconds = currentAd.displayTime ?: 10,
                        onFinished = onAdFinished
                    )
                }
            }
        }
    }
}

@Composable
fun HtmlContent(
    url: String, 
    displayTimeSeconds: Int, 
    onFinished: () -> Unit,
    modifier: Modifier = Modifier
) {
    val webViewState = rememberWebViewState(url = url)
    
    LaunchedEffect(url) {
        delay(displayTimeSeconds * 1000L)
        onFinished()
    }

    WebView(
        state = webViewState,
        modifier = modifier.fillMaxSize(),
        onCreated = { webView ->
            @Suppress("SetJavaScriptEnabled")
            webView.settings.javaScriptEnabled = true
            webView.settings.domStorageEnabled = true
        }
    )
}

@Composable
fun ImageContent(
    imageFile: File, 
    displayTimeSeconds: Int, 
    onFinished: () -> Unit,
    modifier: Modifier = Modifier
) {
    val bitmap = remember(imageFile.absolutePath) {
        if (imageFile.exists()) {
            BitmapFactory.decodeFile(imageFile.absolutePath)
        } else null
    }

    LaunchedEffect(imageFile.absolutePath) {
        delay(displayTimeSeconds * 1000L)
        onFinished()
    }

    bitmap?.let {
        Image(
            bitmap = it.asImageBitmap(),
            contentDescription = null,
            modifier = modifier.fillMaxSize(),
            contentScale = ContentScale.Fit
        )
    }
}

@OptIn(UnstableApi::class)
@Composable
fun VideoContent(
    videoFile: File, 
    onFinished: () -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current

    val exoPlayer = remember(videoFile.absolutePath) {
        ExoPlayer.Builder(context).build().apply {
            val mediaItem = MediaItem.fromUri(videoFile.absolutePath)
            setMediaItem(mediaItem)
            prepare()
            playWhenReady = true
            repeatMode = Player.REPEAT_MODE_OFF
            
            addListener(object : Player.Listener {
                override fun onPlaybackStateChanged(state: Int) {
                    if (state == Player.STATE_ENDED) {
                        onFinished()
                    }
                }
                override fun onPlayerError(error: androidx.media3.common.PlaybackException) {
                    android.util.Log.e("VideoContent", "Playback error", error)
                    onFinished() // Skip broken videos
                }
            })
        }
    }

    DisposableEffect(videoFile.absolutePath) {
        onDispose {
            exoPlayer.release()
        }
    }

    AndroidView(
        factory = { ctx ->
            PlayerView(ctx).apply {
                player = exoPlayer
                useController = false
                resizeMode = androidx.media3.ui.AspectRatioFrameLayout.RESIZE_MODE_ZOOM
            }
        },
        modifier = modifier.fillMaxSize()
    )
}
