package com.example.signage_front.ui.screens

import android.graphics.BitmapFactory
import android.view.TextureView
import androidx.annotation.OptIn
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import com.example.signage_front.data.AdStatus
import com.example.signage_front.network.MediaManager
import com.example.signage_front.network.Config
import com.example.signage_front.ui.composables.FaceDetectionCameraPreview
import androidx.compose.foundation.layout.padding
import androidx.compose.ui.unit.dp
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

    android.util.Log.d("AdScreen", "Composing AdScreen: ads.size=${ads.size}, currentIndex=$currentIndex, effectiveIndex=${if (ads.isNotEmpty()) currentIndex % ads.size else -1}")
    ads.forEachIndexed { idx, ad ->
        android.util.Log.d("AdScreen", "  Ad[$idx]: id=${ad.adId}, mediaType='${ad.mediaType}', path=${ad.path}, syncStatus=${ad.syncStatus}")
    }
    android.util.Log.d("AdScreen", "Current ad: adId=${currentAd?.adId}, mediaType='${currentAd?.mediaType}'")

    if (currentAd == null) {
        Box(modifier = modifier.fillMaxSize())
        return
    }

    val context = LocalContext.current
    val localFile = MediaManager.getLocalFile(context, currentAd)

    val onAdFinished: () -> Unit = {
        android.util.Log.d("AdScreen", "onAdFinished called, advancing from index $currentIndex")
        currentIndex += 1
    }

    // Use a Box with black background to prevent any flicker
    Box(
        modifier = modifier
            .fillMaxSize()
            .background(Color.Black)
            .clickable {
                currentAd.url?.let { onAdClick(it) }
            }
    ) {
        // Use currentIndex as part of the key to force re-composition
        key(currentIndex, currentAd.adId) {
            when (currentAd.mediaType?.lowercase()) {
                "video" -> {
                    VideoContent(
                        videoFile = localFile,
                        playbackId = currentIndex,
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
                else -> {
                    android.util.Log.e("AdScreen", "Unknown mediaType '${currentAd.mediaType}' for ad ${currentAd.adId}, skipping...")
                    LaunchedEffect(currentAd.adId) {
                        onAdFinished()
                    }
                }
            }
        }

        // Add the Face Detection background analyzer and dev PIP HUD
        var faceState by remember { mutableStateOf("No face detected") }
        FaceDetectionCameraPreview(
            onFaceAnalyzed = { result ->
                if (result.isFacePresent) {
                    val stateStr = "Face Detected | Age: ${result.age ?: "Unknown"} | Gender: ${result.gender ?: "Unknown"}"
                    if (stateStr != faceState) {
                        faceState = stateStr
                        android.util.Log.d("AdScreen", "Audience Analysis update: $stateStr")
                    }
                } else {
                    if (faceState != "No face detected") {
                        faceState = "No face detected"
                        android.util.Log.d("AdScreen", "Audience Analysis update: No face detected")
                    }
                }
            },
            showPreview = Config.ENV == "dev",
            modifier = Modifier
                .align(androidx.compose.ui.Alignment.TopEnd)
                .padding(16.dp)
        )
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
    android.util.Log.d("ImageContent", "ImageContent composing: file=${imageFile.name}, exists=${imageFile.exists()}, displayTime=$displayTimeSeconds")

    val bitmap = remember(imageFile.absolutePath) {
        val exists = imageFile.exists()
        android.util.Log.d("ImageContent", "Loading bitmap: path=${imageFile.absolutePath}, exists=$exists")
        if (exists) {
            val bmp = BitmapFactory.decodeFile(imageFile.absolutePath)
            android.util.Log.d("ImageContent", "Bitmap loaded: ${bmp?.width}x${bmp?.height}, null=${bmp == null}")
            bmp
        } else {
            android.util.Log.e("ImageContent", "Image file does not exist: ${imageFile.absolutePath}")
            null
        }
    }

    LaunchedEffect(imageFile.absolutePath) {
        android.util.Log.d("ImageContent", "Starting display timer: ${displayTimeSeconds}s for ${imageFile.name}")
        delay(displayTimeSeconds * 1000L)
        onFinished()
    }

    if (bitmap != null) {
        android.util.Log.d("ImageContent", "Rendering image: ${bitmap.width}x${bitmap.height}")
        Image(
            bitmap = bitmap.asImageBitmap(),
            contentDescription = null,
            modifier = modifier.fillMaxSize(),
            contentScale = ContentScale.Fit
        )
    } else {
        android.util.Log.e("ImageContent", "Cannot render - bitmap is null!")
        LaunchedEffect(Unit) {
            onFinished()
        }
    }
}

/**
 * Video content using TextureView instead of SurfaceView.
 * TextureView doesn't have the Z-ordering issues that SurfaceView has,
 * which can cause black screens when switching between video and other content.
 */
@OptIn(UnstableApi::class)
@Composable
fun VideoContent(
    videoFile: File,
    playbackId: Int,
    onFinished: () -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current

    android.util.Log.d("VideoContent", "VideoContent composing with playbackId=$playbackId, file=${videoFile.name}")

    // State to track if video has finished - prevents multiple onFinished calls
    var hasFinished by remember(playbackId) { mutableStateOf(false) }

    val exoPlayer = remember(playbackId) {
        android.util.Log.d("VideoContent", "Creating new ExoPlayer for playbackId=$playbackId, file=${videoFile.absolutePath}")
        ExoPlayer.Builder(context).build().apply {
            val mediaItem = MediaItem.fromUri(videoFile.absolutePath)
            setMediaItem(mediaItem)
            prepare()
            playWhenReady = true
            repeatMode = Player.REPEAT_MODE_OFF

            addListener(object : Player.Listener {
                override fun onPlaybackStateChanged(state: Int) {
                    val stateName = when (state) {
                        Player.STATE_IDLE -> "IDLE"
                        Player.STATE_BUFFERING -> "BUFFERING"
                        Player.STATE_READY -> "READY"
                        Player.STATE_ENDED -> "ENDED"
                        else -> "UNKNOWN($state)"
                    }
                    android.util.Log.d("VideoContent", "Playback state: $stateName (playbackId=$playbackId)")
                    if (state == Player.STATE_ENDED && !hasFinished) {
                        hasFinished = true
                        onFinished()
                    }
                }

                override fun onPlayerError(error: androidx.media3.common.PlaybackException) {
                    android.util.Log.e("VideoContent", "Playback error (playbackId=$playbackId): ${error.message}", error)
                    if (!hasFinished) {
                        hasFinished = true
                        onFinished()
                    }
                }
            })
        }
    }

    DisposableEffect(playbackId) {
        onDispose {
            android.util.Log.d("VideoContent", "Releasing ExoPlayer (playbackId=$playbackId)")
            exoPlayer.stop()
            exoPlayer.clearMediaItems()
            exoPlayer.release()
        }
    }

    // Use TextureView directly instead of PlayerView with SurfaceView
    // TextureView works better with Compose's view hierarchy and doesn't have Z-ordering issues
    AndroidView(
        factory = { ctx ->
            android.util.Log.d("VideoContent", "Creating TextureView (playbackId=$playbackId)")
            TextureView(ctx).also { textureView ->
                exoPlayer.setVideoTextureView(textureView)
            }
        },
        modifier = modifier.fillMaxSize(),
        onRelease = { textureView ->
            android.util.Log.d("VideoContent", "Releasing TextureView (playbackId=$playbackId)")
            exoPlayer.clearVideoTextureView(textureView)
        }
    )
}
