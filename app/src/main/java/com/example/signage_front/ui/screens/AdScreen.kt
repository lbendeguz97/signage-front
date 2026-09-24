package com.example.signage_front.ui.screens

import android.graphics.BitmapFactory
import android.view.TextureView
import androidx.annotation.OptIn
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.Spring
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.snapping.SnapLayoutInfoProvider
import androidx.compose.foundation.gestures.snapping.rememberSnapFlingBehavior
import androidx.compose.foundation.gestures.snapping.SnapPosition
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.colorResource
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import com.example.signage_front.R
import com.example.signage_front.network.SspCacheManager
import com.example.signage_front.data.CachedSspAd
import androidx.media3.exoplayer.ExoPlayer
import com.example.signage_front.data.AdStatus
import com.example.signage_front.data.PlaylistItem
import com.example.signage_front.data.AdDisplayLog
import com.example.signage_front.data.AdRepository
import com.example.signage_front.network.AdScheduler
import com.example.signage_front.network.MediaManager
import com.example.signage_front.ui.composables.FaceDetectionCameraPreview
import com.multiplatform.webview.web.WebView
import com.multiplatform.webview.web.rememberWebViewState
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.Dispatchers
import java.io.File

@Composable
fun AdScreen(
    items: List<PlaylistItem>,
    onAdClick: (String) -> Unit,
    onBackToHome: () -> Unit,
    onNavigateToDebug: () -> Unit,
    modifier: Modifier = Modifier,
    pendingInterruptPriority: String? = null,
    onTriggerLoopComplete: (() -> Unit)? = null,
    isIdle: Boolean = false
) {
    // While idling, render nothing so players/webviews are disposed (no ad, no audio).
    if (isIdle) {
        Box(modifier = modifier.fillMaxSize().background(Color.Black))
        return
    }

    var currentIndex by remember { mutableIntStateOf(0) }
    val currentItem = if (items.isNotEmpty()) items[currentIndex % items.size] else null

    // The AdStatus backing the current item, if any (used for play-session logging).
    val currentAd = when (currentItem) {
        is PlaylistItem.Standard -> currentItem.adStatus
        else -> null
    }

    android.util.Log.d("AdScreen", "Composing AdScreen: items.size=${items.size}, currentIndex=$currentIndex, effectiveIndex=${if (items.isNotEmpty()) currentIndex % items.size else -1}")
    items.forEachIndexed { idx, item ->
        android.util.Log.d("AdScreen", "  Item[$idx]: ${describeItem(item)}")
    }
    android.util.Log.d("AdScreen", "Current item: ${currentItem?.let { describeItem(it) }}")

    if (currentItem == null) {
        Box(modifier = modifier.fillMaxSize())
        return
    }

    // Effective display time for the current item (used by image/html/ssp branches).
    val effectiveDisplayTime = when (currentItem) {
        is PlaylistItem.Standard -> currentItem.durationOverride ?: currentItem.adStatus.displayTime ?: 10
        is PlaylistItem.VirtualSsp -> currentItem.durationBudget
        else -> 10
    }

    val context = LocalContext.current
    val repository = remember(context) { AdRepository(context) }
    val scope = rememberCoroutineScope()

    // Single ExoPlayer reused by every video source on this screen. It is created
    // once and released when the ad screen leaves; individual items only swap the
    // media item, so looping an ad no longer spawns a new player every cycle.
    val exoPlayer = remember { ExoPlayer.Builder(context).build() }
    DisposableEffect(Unit) {
        onDispose {
            exoPlayer.stop()
            exoPlayer.release()
        }
    }

    // Tracks the active ad play session. Only backed-by-an-ad items (Standard/VirtualSsp)
    // produce a loggable session; logo/group-SSP items have no ad id.
    val playSession = remember(currentIndex, currentAd?.adId) {
        if (currentAd != null) AdPlaySession(currentAd.adId) else null
    }

    // Helper function to save a play log session (no-op when no session is active)
    val savePlayLog: (AdPlaySession?) -> Unit = remember(repository, context) {
        { session ->
            if (session != null && !session.logSaved) {
                session.logSaved = true
                val duration = System.currentTimeMillis() - session.startTime
                val log = AdDisplayLog(
                    adId = session.adId,
                    timestamp = session.startTime,
                    durationMs = duration,
                    clicked = session.clicked,
                    exitedScreen = session.exitedScreen,
                    audienceAge = session.audienceAge,
                    audienceGender = session.audienceGender
                )
                android.util.Log.d("AdScreenLog", "Logging ad display: adId=${log.adId}, duration=${log.durationMs}ms, clicked=${log.clicked}, exited=${log.exitedScreen}, age=${log.audienceAge}, gender=${log.audienceGender}")
                scope.launch(Dispatchers.IO) {
                    try {
                        repository.insertDisplayLog(log)
                        val logSyncTime = AdScheduler.getLogSyncTime(context)
                        if (logSyncTime == 0) {
                            AdScheduler.uploadPendingLogs(context)
                        }
                    } catch (e: Exception) {
                        android.util.Log.e("AdScreenLog", "Failed to save display log", e)
                    }
                }
            }
        }
    }

    // Track screen exit (compositions disposal)
    DisposableEffect(playSession) {
        onDispose {
            if (playSession != null && !playSession.logSaved) {
                playSession.exitedScreen = true
                savePlayLog(playSession)
            }
        }
    }

    // Interrupt timing flags (high/medium/low) per ORCHESTRATION_PLAN §4.
    // The items list is already swapped to the trigger head by the caller; these flags
    // control when a full cycle is considered complete (used for trigger completion).
    var interruptAfterCurrentItem by remember { mutableStateOf(false) }
    var interruptAfterLoop by remember { mutableStateOf(false) }
    LaunchedEffect(pendingInterruptPriority) {
        when (pendingInterruptPriority) {
            "medium" -> {
                interruptAfterCurrentItem = true
                android.util.Log.d("AdScreen", "Medium interrupt: will cut after current item")
            }
            "low" -> {
                interruptAfterLoop = true
                android.util.Log.d("AdScreen", "Low interrupt: will cut after current loop")
            }
            "high" -> android.util.Log.d("AdScreen", "High interrupt: cutting immediately")
        }
    }

    val onAdFinished: () -> Unit = {
        android.util.Log.d("AdScreen", "onAdFinished called, advancing from index $currentIndex")
        if (playSession != null && !playSession.logSaved) {
            savePlayLog(playSession)
        }
        currentIndex += 1
        interruptAfterCurrentItem = false
        if (items.isNotEmpty() && (currentIndex % items.size) == 0) {
            interruptAfterLoop = false
            onTriggerLoopComplete?.invoke()
        }
    }

    // Use a Box with black background to prevent any flicker
    Box(
        modifier = modifier
            .fillMaxSize()
            .background(Color.Black)
            .clickable {
                if (playSession != null && !playSession.logSaved) {
                    playSession.clicked = true
                    playSession.exitedScreen = true
                    savePlayLog(playSession)
                }
                (currentItem as? PlaylistItem.Standard)?.adStatus?.url?.let { onAdClick(it) }
            }
    ) {
        // Persistent video output surface: mounted once for the whole ad screen and
        // reused across every item and loop, so no per-cycle TextureView/player churn.
        AndroidView(
            factory = { ctx ->
                TextureView(ctx).also { exoPlayer.setVideoTextureView(it) }
            },
            onRelease = { textureView -> exoPlayer.clearVideoTextureView(textureView) },
            modifier = Modifier.fillMaxSize()
        )

        // Detach media when the current item never uses the player (image/html/logo).
        // Video and SSP items attach their own media item further below.
        LaunchedEffect(currentItem) {
            val mediaType = (currentItem as? PlaylistItem.Standard)?.adStatus?.mediaType?.lowercase()
            val usesPlayer = mediaType == "video" || mediaType == "ssp" ||
                currentItem is PlaylistItem.VirtualSsp ||
                currentItem is PlaylistItem.GroupSspSlot
            if (!usesPlayer) {
                exoPlayer.stop()
                exoPlayer.clearMediaItems()
            }
        }

        // Use currentIndex as part of the key to force re-composition
        key(currentIndex, currentAd?.adId) {
            when (val item = currentItem) {
                is PlaylistItem.Standard -> {
                    val ad = item.adStatus
                    val file = MediaManager.getLocalFile(context, ad)
                    when (ad.mediaType?.lowercase()) {
                        "video" -> {
                            VideoContent(
                                player = exoPlayer,
                                videoFile = file,
                                playbackId = currentIndex,
                                onFinished = onAdFinished
                            )
                        }
                        "image" -> {
                            ImageContent(
                                imageFile = file,
                                displayTimeSeconds = effectiveDisplayTime,
                                onFinished = onAdFinished
                            )
                        }
                        "html" -> {
                            HtmlContent(
                                url = ad.url ?: "",
                                displayTimeSeconds = effectiveDisplayTime,
                                onFinished = onAdFinished
                            )
                        }
                        "ssp" -> {
                            SspContent(
                                player = exoPlayer,
                                playbackId = currentIndex,
                                onFinished = onAdFinished,
                                onAdClick = onAdClick
                            )
                        }
                        else -> {
                            android.util.Log.e("AdScreen", "Unknown mediaType '${ad.mediaType}' for ad ${ad.adId}, skipping...")
                            LaunchedEffect(ad.adId) {
                                onAdFinished()
                            }
                        }
                    }
                }
                is PlaylistItem.VirtualSsp -> {
                    SspContent(
                        player = exoPlayer,
                        playbackId = currentIndex,
                        onFinished = onAdFinished,
                        onAdClick = onAdClick,
                        durationBudgetMs = item.durationBudget * 1000L,
                        fallbackFile = item.fallbackFile
                    )
                }
                is PlaylistItem.GroupSspSlot -> {
                    SspContent(
                        player = exoPlayer,
                        playbackId = currentIndex,
                        onFinished = onAdFinished,
                        onAdClick = onAdClick,
                        durationBudgetMs = 0L,
                        fallbackFile = item.fallbackFile
                    )
                }
                PlaylistItem.Logo -> {
                    LogoContent(
                        onFinished = onAdFinished
                    )
                }
                else -> {
                    // currentItem is null (guarded earlier) — no-op to satisfy exhaustiveness
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
                    // Capture demographics inside active playSession
                    if (playSession != null) {
                        playSession.audienceAge = result.age?.toString() ?: playSession.audienceAge
                        playSession.audienceGender = result.gender ?: playSession.audienceGender
                    }
                } else {
                    if (faceState != "No face detected") {
                        faceState = "No face detected"
                        android.util.Log.d("AdScreen", "Audience Analysis update: No face detected")
                    }
                }
            },
            showPreview = false,
            modifier = Modifier
                .align(androidx.compose.ui.Alignment.TopEnd)
                .padding(16.dp)
        )
    }
}

private class AdPlaySession(
    val adId: String,
    val startTime: Long = System.currentTimeMillis()
) {
    var clicked: Boolean = false
    var exitedScreen: Boolean = false
    var logSaved: Boolean = false
    var audienceAge: String? = null
    var audienceGender: String? = null
}

private fun describeItem(item: PlaylistItem): String = when (item) {
    is PlaylistItem.Standard -> "Standard(adId=${item.adStatus.adId}, mediaType='${item.adStatus.mediaType}', path=${item.adStatus.path}, durationOverride=${item.durationOverride})"
    is PlaylistItem.VirtualSsp -> "VirtualSsp(adStatusId=${item.adStatusId}, durationBudget=${item.durationBudget})"
    is PlaylistItem.GroupSspSlot -> "GroupSspSlot(connectivityId=${item.connectivity.id})"
    PlaylistItem.Logo -> "Logo"
}

@Composable
fun LogoContent(
    onFinished: () -> Unit,
    displayTimeSeconds: Int = 10,
    modifier: Modifier = Modifier
) {
    LaunchedEffect(Unit) {
        delay(displayTimeSeconds * 1000L)
        onFinished()
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
 * Plays [videoFile] on the shared [player] owned by AdScreen. This composable only
 * swaps the media item and manages the per-play listener; the player and its output
 * surface are created once by the caller, so no player/TextureView is allocated here.
 */
@OptIn(UnstableApi::class)
@Composable
fun VideoContent(
    player: ExoPlayer,
    videoFile: File,
    playbackId: Int,
    onFinished: () -> Unit,
    onProgress: ((Float) -> Unit)? = null,
    maxDurationMs: Long? = null
) {
    android.util.Log.d("VideoContent", "VideoContent composing with playbackId=$playbackId, file=${videoFile.name}")

    val currentOnFinished by rememberUpdatedState(onFinished)

    // State to track if video has finished - prevents multiple onFinished calls
    var hasFinished by remember(playbackId) { mutableStateOf(false) }

    // (Re)start the media whenever the play token or media file changes.
    LaunchedEffect(playbackId, videoFile.absolutePath) {
        android.util.Log.d("VideoContent", "Starting media on shared player (playbackId=$playbackId, file=${videoFile.absolutePath})")
        hasFinished = false
        player.setMediaItem(MediaItem.fromUri(videoFile.absolutePath))
        player.repeatMode = Player.REPEAT_MODE_OFF
        player.prepare()
        player.playWhenReady = true
    }

    DisposableEffect(playbackId, player) {
        val listener = object : Player.Listener {
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
                    currentOnFinished()
                }
            }

            override fun onPlayerError(error: androidx.media3.common.PlaybackException) {
                android.util.Log.e("VideoContent", "Playback error (playbackId=$playbackId): ${error.message}", error)
                if (!hasFinished) {
                    hasFinished = true
                    currentOnFinished()
                }
            }
        }
        player.addListener(listener)
        onDispose { player.removeListener(listener) }
    }

    // Poll playback position (used for VAST quartile tracking)
    LaunchedEffect(playbackId, onProgress) {
        if (onProgress == null) return@LaunchedEffect
        while (true) {
            val duration = player.duration
            if (duration > 0) {
                onProgress((player.currentPosition.toFloat() / duration).coerceIn(0f, 1f))
            }
            delay(500)
        }
    }

    // Optional hard cap (used for fallback media filling a fixed slot remainder)
    LaunchedEffect(playbackId, maxDurationMs) {
        val cap = maxDurationMs ?: return@LaunchedEffect
        delay(cap)
        if (!hasFinished) {
            hasFinished = true
            player.pause()
            currentOnFinished()
        }
    }
}

@Composable
fun SspContent(
    player: ExoPlayer,
    playbackId: Int,
    onFinished: () -> Unit,
    onAdClick: (String) -> Unit,
    durationBudgetMs: Long = 0L,   // 0 = unlimited (pure group SSP)
    fallbackFile: File? = null
) {
    val context = LocalContext.current
    val repository = remember(context) { AdRepository(context) }
    val scope = rememberCoroutineScope()

    var isLoading by remember(playbackId) { mutableStateOf(true) }
    var queue by remember(playbackId) { mutableStateOf<List<CachedSspAd>>(emptyList()) }
    var queueIndex by remember(playbackId) { mutableIntStateOf(0) }
    var elapsedMs by remember(playbackId) { mutableLongStateOf(0L) }
    var playedAds by remember(playbackId) { mutableIntStateOf(0) }
    var fallbackShown by remember(playbackId) { mutableStateOf(false) }
    var ended by remember(playbackId) { mutableStateOf(false) }

    // Build the play queue once: valid, existing, deduped cached ads in LRU order.
    LaunchedEffect(playbackId) {
        SspCacheManager.evictExpiredAndLru(context)
        val now = System.currentTimeMillis()
        queue = repository.configDao.getAllCachedSspAds()
            .filter { it.expiresAt > now && File(it.localPath).exists() }
            .distinctBy { it.mediaUrl }
        android.util.Log.d("AdScreen", "SSP Slot: ${queue.size} cached ad(s) ready (budget=${if (durationBudgetMs > 0) durationBudgetMs else "unlimited"}ms)")
        isLoading = false
    }

    if (isLoading) {
        Box(modifier = Modifier.fillMaxSize().background(Color.Black))
        return
    }

    fun endSlot() {
        if (ended) return
        ended = true
        val overrun = if (durationBudgetMs > 0) (elapsedMs - durationBudgetMs).coerceAtLeast(0) else 0L
        android.util.Log.d("AdScreen", "SSP Slot ended: filled=${elapsedMs}ms budget=${durationBudgetMs}ms overrun=${overrun}ms ads=$playedAds fallback=$fallbackShown")
        scope.launch {
            repository.logSspSlot(durationBudgetMs, elapsedMs, overrun, playedAds, fallbackShown)
        }
        onFinished()
    }

    fun remainingMs(): Long {
        if (durationBudgetMs <= 0) return SSP_FALLBACK_DEFAULT_MS
        return (durationBudgetMs - elapsedMs).coerceAtLeast(1)
    }

    fun onAdCompleted(actualMs: Long) {
        elapsedMs += actualMs
        playedAds++
        if (durationBudgetMs > 0 && elapsedMs >= durationBudgetMs) {
            endSlot()
        } else if (queueIndex + 1 < queue.size) {
            queueIndex++
        } else if (fallbackFile != null && !fallbackShown) {
            // Exhaust the queue so currentAd becomes null and the fallback branch renders.
            queueIndex = queue.size
            fallbackShown = true
        } else {
            endSlot()
        }
    }

    val currentAd = queue.getOrNull(queueIndex)
    if (ended) return

    when {
        currentAd != null -> {
            key(queueIndex) {
                SspCachedAdView(
                    player = player,
                    playbackId = playbackId + queueIndex,
                    ad = currentAd,
                    onAdClick = onAdClick,
                    onCompleted = { actualMs -> onAdCompleted(actualMs) }
                )
            }
        }
        fallbackFile != null && !fallbackShown -> {
            key("fallback") {
                SspFallbackView(
                    player = player,
                    playbackId = playbackId,
                    fallbackFile = fallbackFile,
                    durationMs = remainingMs(),
                    onCompleted = {
                        elapsedMs += remainingMs()
                        endSlot()
                    }
                )
            }
        }
        else -> {
            // Nothing to display (no cached ad, no fallback) -> end the slot.
            LaunchedEffect(Unit) { endSlot() }
            Box(modifier = Modifier.fillMaxSize().background(Color.Black))
        }
    }
}

private const val SSP_FALLBACK_DEFAULT_MS = 10_000L

/**
 * Displays one cached SSP creative and fires its tracking beacons
 * (impressions, creativeView, start, quartiles, complete).
 */
@Composable
private fun SspCachedAdView(
    player: ExoPlayer,
    playbackId: Int,
    ad: CachedSspAd,
    onAdClick: (String) -> Unit,
    onCompleted: (Long) -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val file = File(ad.localPath)
    val metadata = remember(ad.mediaUrl) { SspCacheManager.getSspMetadata(context, ad.mediaUrl) }
    val firedEvents = remember(ad.mediaUrl) { mutableSetOf<String>() }

    fun fireTracking(event: String) {
        val url = metadata?.trackingUrls?.get(event) ?: return
        if (!firedEvents.add(event)) return
        scope.launch(Dispatchers.IO) {
            SspCacheManager.fireImpressionBeacons(context, listOf(url))
        }
    }

    fun fireTrackingProgress(fraction: Float) {
        if (fraction >= 0.25f) fireTracking("firstQuartile")
        if (fraction >= 0.50f) fireTracking("midpoint")
        if (fraction >= 0.75f) fireTracking("thirdQuartile")
    }

    LaunchedEffect(ad.mediaUrl) {
        metadata?.impressionUrls?.let { urls ->
            scope.launch(Dispatchers.IO) {
                SspCacheManager.fireImpressionBeacons(context, urls)
            }
        }
        fireTracking("creativeView")
        fireTracking("start")
    }

    val displaySeconds = (ad.durationSeconds.takeIf { it > 0 } ?: metadata?.durationSeconds ?: 10).coerceAtLeast(1)
    val handleFinished = {
        fireTracking("complete")
        onCompleted(displaySeconds * 1000L)
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .clickable {
                metadata?.redirectUrl?.let { redirect ->
                    metadata.clickTrackingUrls.let { clickUrls ->
                        scope.launch(Dispatchers.IO) {
                            SspCacheManager.fireImpressionBeacons(context, clickUrls)
                        }
                    }
                    onAdClick(redirect)
                }
            }
    ) {
        when (ad.mediaType.lowercase()) {
            "video" -> {
                VideoContent(
                    player = player,
                    videoFile = file,
                    playbackId = playbackId,
                    onFinished = handleFinished,
                    onProgress = { fraction -> fireTrackingProgress(fraction) }
                )
            }
            "image" -> {
                val stepMs = displaySeconds * 1000L / 4
                LaunchedEffect(ad.mediaUrl, displaySeconds) {
                    repeat(3) {
                        delay(stepMs)
                        fireTrackingProgress((it + 1) / 4f)
                    }
                }
                ImageContent(
                    imageFile = file,
                    displayTimeSeconds = displaySeconds,
                    onFinished = handleFinished
                )
            }
            else -> {
                LaunchedEffect(Unit) { handleFinished() }
            }
        }
    }
}

/**
 * Displays the group's fallback media to fill the remaining SSP slot budget.
 */
@Composable
private fun SspFallbackView(
    player: ExoPlayer,
    playbackId: Int,
    fallbackFile: File,
    durationMs: Long,
    onCompleted: () -> Unit
) {
    android.util.Log.d("AdScreen", "SSP Slot: using fallback media ${fallbackFile.name} for ${durationMs}ms")
    val isVideo = fallbackFile.name.substringAfterLast('.', "").lowercase() in setOf("mp4", "mkv", "webm", "avi")
    val displaySeconds = (durationMs / 1000L).coerceAtLeast(1)
    Box(modifier = Modifier.fillMaxSize().background(Color.Black)) {
        if (isVideo) {
            VideoContent(
                player = player,
                videoFile = fallbackFile,
                playbackId = playbackId,
                onFinished = onCompleted,
                maxDurationMs = durationMs
            )
        } else {
            ImageContent(
                imageFile = fallbackFile,
                displayTimeSeconds = displaySeconds.toInt(),
                onFinished = onCompleted
            )
        }
    }
}
