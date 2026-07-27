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
import androidx.media3.exoplayer.ExoPlayer
import com.example.signage_front.data.AdStatus
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
    ads: List<AdStatus>,
    onAdClick: (String) -> Unit,
    onBackToHome: () -> Unit,
    onNavigateToDebug: () -> Unit,
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
    val repository = remember(context) { AdRepository(context) }
    val scope = rememberCoroutineScope()

    // Tracks the active ad play session
    val playSession = remember(currentIndex, currentAd.adId) {
        AdPlaySession(currentAd.adId)
    }

    // Helper function to save a play log session
    val savePlayLog: (AdPlaySession) -> Unit = remember(repository, context) {
        { session ->
            if (!session.logSaved) {
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
            if (!playSession.logSaved) {
                playSession.exitedScreen = true
                savePlayLog(playSession)
            }
        }
    }

    val localFile = MediaManager.getLocalFile(context, currentAd)

    val onAdFinished: () -> Unit = {
        android.util.Log.d("AdScreen", "onAdFinished called, advancing from index $currentIndex")
        if (!playSession.logSaved) {
            savePlayLog(playSession)
        }
        currentIndex += 1
    }

    val density = LocalDensity.current
    val edgeThresholdPx = remember(density) { with(density) { 60.dp.toPx() } }
    val dragThresholdPx = remember(density) { with(density) { 50.dp.toPx() } }
    var showMenu by remember { mutableStateOf(false) }
    var menuInteractionTime by remember { mutableLongStateOf(0L) }

    val menuItems = remember {
        listOf(
            MenuItem("Éttermek", Icons.Filled.Restaurant) {
                if (!playSession.logSaved) {
                    playSession.exitedScreen = true
                    savePlayLog(playSession)
                }
                showMenu = false
                onBackToHome()
            },
            MenuItem("Szállodák", Icons.Filled.Hotel) {
                if (!playSession.logSaved) {
                    playSession.exitedScreen = true
                    savePlayLog(playSession)
                }
                showMenu = false
                onBackToHome()
            },
            MenuItem("Szórakozás", Icons.Filled.TheaterComedy) {
                if (!playSession.logSaved) {
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
                if (!playSession.logSaved) {
                    playSession.clicked = true
                    playSession.exitedScreen = true
                    savePlayLog(playSession)
                }
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
                    // Capture demographics inside active playSession
                    playSession.audienceAge = result.age?.toString() ?: playSession.audienceAge
                    playSession.audienceGender = result.gender ?: playSession.audienceGender
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

                        Box(
                            modifier = Modifier
                                .width(boxWidth)
                                .height(60.dp)
                                .background(Color.Black, shape = RoundedCornerShape(12.dp))
                                .border(2.dp, Color.White, shape = RoundedCornerShape(12.dp))
                                .clickable { item.onClick() },
                            contentAlignment = Alignment.CenterStart
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
                                Row(
                                    modifier = Modifier
                                        .padding(start = 56.dp, end = 20.dp)
                                        .fillMaxHeight(),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Text(
                                        text = item.text,
                                        color = Color.White,
                                        style = MaterialTheme.typography.titleMedium,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
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
