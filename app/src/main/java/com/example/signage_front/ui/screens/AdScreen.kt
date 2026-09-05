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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Restaurant
import androidx.compose.material.icons.filled.Hotel
import androidx.compose.material.icons.filled.TheaterComedy
import androidx.compose.material.icons.filled.Campaign
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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import com.example.signage_front.network.SspCacheManager
import com.example.signage_front.data.CachedSspAd
import androidx.media3.exoplayer.ExoPlayer
import com.example.signage_front.data.AdStatus
import com.example.signage_front.data.PlaylistItem
import com.example.signage_front.data.AdDisplayLog
import com.example.signage_front.data.AdRepository
import com.example.signage_front.network.AdScheduler
import com.example.signage_front.network.MediaManager
import com.example.signage_front.network.Config
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
    onTriggerLoopComplete: (() -> Unit)? = null
) {
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

    val density = LocalDensity.current
    val edgeThresholdPx = remember(density) { with(density) { 60.dp.toPx() } }
    val dragThresholdPx = remember(density) { with(density) { 50.dp.toPx() } }
    var showMenu by remember { mutableStateOf(false) }
    var menuInteractionTime by remember { mutableLongStateOf(0L) }

    val menuItems = remember {
        listOf(
            MenuItem("Éttermek", Icons.Filled.Restaurant) {
                if (playSession != null && !playSession.logSaved) {
                    playSession.exitedScreen = true
                    savePlayLog(playSession)
                }
                showMenu = false
                onBackToHome()
            },
            MenuItem("Szállodák", Icons.Filled.Hotel) {
                if (playSession != null && !playSession.logSaved) {
                    playSession.exitedScreen = true
                    savePlayLog(playSession)
                }
                showMenu = false
                onBackToHome()
            },
            MenuItem("Szórakozás", Icons.Filled.TheaterComedy) {
                if (playSession != null && !playSession.logSaved) {
                    playSession.exitedScreen = true
                    savePlayLog(playSession)
                }
                showMenu = false
                onBackToHome()
            },
            MenuItem("Reklám", Icons.Filled.Campaign) {
                showMenu = false
            }
        )
    }

    var visibleItemsCount by remember { mutableIntStateOf(0) }
    var isMenuExpansionUnlocked by remember { mutableStateOf(false) }
    LaunchedEffect(showMenu) {
        if (showMenu) {
            isMenuExpansionUnlocked = false
            visibleItemsCount = 0
            for (i in 1..menuItems.size) {
                delay(120L)
                visibleItemsCount = i
            }
            delay(150L) // Wait for items to fully settle into their spots
            isMenuExpansionUnlocked = true
        } else {
            isMenuExpansionUnlocked = false
            visibleItemsCount = 0
        }
    }

    // Detect drag from left edge to show the menu
    val swipeModifier = Modifier.pointerInput(Unit) {
        awaitEachGesture {
            val down = awaitFirstDown(requireUnconsumed = false)
            if (down.position.x < edgeThresholdPx) {
                var totalDragX = 0f
                while (true) {
                    val event = awaitPointerEvent()
                    val change = event.changes.firstOrNull { it.id == down.id }
                    if (change == null || !change.pressed) {
                        break
                    }
                    val currentX = change.position.x
                    val previousX = change.previousPosition.x
                    totalDragX += (currentX - previousX)
                    
                    if (totalDragX > dragThresholdPx) {
                        change.consume()
                        showMenu = true
                        menuInteractionTime = System.currentTimeMillis()
                        break
                    }
                }
            }
        }
    }

    // Auto-timeout for the menu overlay after 10 seconds of no interaction
    LaunchedEffect(showMenu, menuInteractionTime) {
        if (showMenu) {
            delay(10000L)
            showMenu = false
        }
    }

    // Use a Box with black background to prevent any flicker
    Box(
        modifier = modifier
            .fillMaxSize()
            .background(Color.Black)
            .then(swipeModifier)
            .clickable {
                if (playSession != null && !playSession.logSaved) {
                    playSession.clicked = true
                    playSession.exitedScreen = true
                    savePlayLog(playSession)
                }
                (currentItem as? PlaylistItem.Standard)?.adStatus?.url?.let { onAdClick(it) }
            }
    ) {
        // Use currentIndex as part of the key to force re-composition
        key(currentIndex, currentAd?.adId) {
            when (val item = currentItem) {
                is PlaylistItem.Standard -> {
                    val ad = item.adStatus
                    val file = MediaManager.getLocalFile(context, ad)
                    when (ad.mediaType?.lowercase()) {
                        "video" -> {
                            VideoContent(
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
                        playbackId = currentIndex,
                        onFinished = onAdFinished,
                        onAdClick = onAdClick,
                        durationBudgetMs = item.durationBudget * 1000L,
                        fallbackFile = item.fallbackFile
                    )
                }
                is PlaylistItem.GroupSspSlot -> {
                    SspContent(
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
            showPreview = Config.ENV == "dev",
            modifier = Modifier
                .align(androidx.compose.ui.Alignment.TopEnd)
                .padding(16.dp)
        )

        // Transparent scrim overlay behind the menu to dismiss it when clicked outside
        if (showMenu) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null
                    ) {
                        showMenu = false
                    }
            )
        }

        val menuOffset by animateDpAsState(
            targetValue = if (showMenu) 0.dp else (-220).dp,
            animationSpec = spring(
                dampingRatio = Spring.DampingRatioNoBouncy,
                stiffness = Spring.StiffnessMedium
            ),
            label = "menuOffset"
        )

        // Floating Sidebar Menu Overlay (No background container or panel behind them)
        BoxWithConstraints(
            modifier = Modifier
                .fillMaxHeight()
                .width(220.dp)
                .offset(x = menuOffset)
                .align(Alignment.CenterStart)
                .pointerInput(Unit) {
                    // Reset the timeout timer on any touch interaction
                    awaitPointerEventScope {
                        while (true) {
                            awaitPointerEvent()
                            menuInteractionTime = System.currentTimeMillis()
                        }
                    }
                }
                .padding(start = 16.dp, top = 16.dp, bottom = 16.dp),
            contentAlignment = Alignment.CenterStart
        ) {
            val maxHeightPx = with(LocalDensity.current) { maxHeight.toPx() }
            val itemHeightPx = with(LocalDensity.current) { 60.dp.toPx() }
            // Calculate scroll offset so item centers align with viewport center.
            val centerScrollOffset = - (maxHeightPx / 2 - itemHeightPx / 2).toInt()

            val centerOffset = 5000 - (5000 % menuItems.size)
            val lazyListState = rememberLazyListState(
                initialFirstVisibleItemIndex = centerOffset,
                initialFirstVisibleItemScrollOffset = centerScrollOffset
            )
            val snappingLayout = remember(lazyListState) {
                SnapLayoutInfoProvider(lazyListState, SnapPosition.Center)
            }
            val snapFlingBehavior = rememberSnapFlingBehavior(snappingLayout)

            var isInitialScrollCompleted by remember { mutableStateOf(false) }

            LaunchedEffect(showMenu) {
                if (showMenu) {
                    isInitialScrollCompleted = false
                    lazyListState.scrollToItem(centerOffset, centerScrollOffset)
                    isInitialScrollCompleted = true
                } else {
                    isInitialScrollCompleted = false
                }
            }

            val centerIndex by remember {
                derivedStateOf {
                    val layoutInfo = lazyListState.layoutInfo
                    val visibleItems = layoutInfo.visibleItemsInfo
                    if (visibleItems.isEmpty()) -1
                    else {
                        val viewportCenter = (layoutInfo.viewportEndOffset - layoutInfo.viewportStartOffset) / 2f
                        visibleItems.minByOrNull { item ->
                            val itemCenter = item.offset + item.size / 2f
                            kotlin.math.abs(itemCenter - viewportCenter)
                        }?.index ?: -1
                    }
                }
            }

            LazyColumn(
                state = lazyListState,
                flingBehavior = snapFlingBehavior,
                modifier = Modifier.fillMaxSize(),
                verticalArrangement = Arrangement.spacedBy(20.dp, Alignment.CenterVertically),
                horizontalAlignment = Alignment.Start
            ) {
                items(10000) { index ->
                    val itemIndex = index % menuItems.size
                    val item = menuItems[itemIndex]
                    val isItemVisible = visibleItemsCount > itemIndex

                    Box(
                        modifier = Modifier
                            .height(60.dp)
                            .width(220.dp),
                        contentAlignment = Alignment.CenterStart
                    ) {
                        AnimatedVisibility(
                            visible = isItemVisible,
                            enter = slideInHorizontally(initialOffsetX = { -it }) + fadeIn(),
                            exit = slideOutHorizontally(targetOffsetX = { -it }) + fadeOut()
                        ) {
                            val isCenter = isInitialScrollCompleted && isMenuExpansionUnlocked && (index == centerIndex)
                            val boxWidth by animateDpAsState(
                                targetValue = if (isCenter) 200.dp else 60.dp,
                                animationSpec = spring(
                                    dampingRatio = Spring.DampingRatioLowBouncy,
                                    stiffness = Spring.StiffnessLow
                                ),
                                label = "boxWidth"
                            )

                            Row(
                                modifier = Modifier
                                    .width(boxWidth)
                                    .height(60.dp)
                                    .background(Color.Black, shape = RoundedCornerShape(12.dp))
                                    .border(2.dp, Color.White, shape = RoundedCornerShape(12.dp))
                                    .clickable { item.onClick() },
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                // Static square container on the left for the icon
                                Box(
                                    modifier = Modifier.size(56.dp),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Icon(
                                        imageVector = item.icon,
                                        contentDescription = item.text,
                                        tint = Color.White,
                                        modifier = Modifier.size(24.dp)
                                    )
                                }

                                // Text displayed only when expanded
                                if (isCenter && boxWidth > 100.dp) {
                                    Text(
                                        text = item.text,
                                        color = Color.White,
                                        style = MaterialTheme.typography.titleMedium,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                        modifier = Modifier.padding(end = 20.dp)
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

private data class MenuItem(
    val text: String,
    val icon: androidx.compose.ui.graphics.vector.ImageVector,
    val onClick: () -> Unit
)

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
            .background(Color.DarkGray),
        contentAlignment = Alignment.Center
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(
                text = "LOGO",
                style = MaterialTheme.typography.displayMedium,
                color = Color.White
            )
            Text(
                text = "Signage fallback",
                style = MaterialTheme.typography.bodyMedium,
                color = Color.White.copy(alpha = 0.6f),
                modifier = Modifier.padding(top = 8.dp)
            )
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
    modifier: Modifier = Modifier,
    onProgress: ((Float) -> Unit)? = null,
    maxDurationMs: Long? = null
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

    // Poll playback position (used for VAST quartile tracking)
    LaunchedEffect(playbackId, onProgress) {
        if (onProgress == null) return@LaunchedEffect
        while (true) {
            val duration = exoPlayer.duration
            if (duration > 0) {
                onProgress((exoPlayer.currentPosition.toFloat() / duration).coerceIn(0f, 1f))
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
            exoPlayer.pause()
            onFinished()
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

@Composable
fun SspContent(
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
