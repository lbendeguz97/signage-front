package com.example.signage_front.ui.composables

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.snapping.SnapLayoutInfoProvider
import androidx.compose.foundation.gestures.snapping.SnapPosition
import androidx.compose.foundation.gestures.snapping.rememberSnapFlingBehavior
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay

data class SidebarItem(
    val text: String,
    val icon: ImageVector,
    val onClick: () -> Unit
)

/**
 * Global sliding sidebar overlay, shown on every screen. The caller owns the
 * `visible` state and the edge-swipe trigger; this composable renders the scrim
 * plus the animated, center-snapping item list.
 */
@Composable
fun RootSidebar(
    items: List<SidebarItem>,
    visible: Boolean,
    onScrimClick: () -> Unit,
    onInteraction: () -> Unit,
    modifier: Modifier = Modifier
) {
    if (items.isEmpty()) return

    var visibleItemsCount by remember { mutableIntStateOf(0) }
    var isMenuExpansionUnlocked by remember { mutableStateOf(false) }

    LaunchedEffect(visible, items.size) {
        if (visible) {
            isMenuExpansionUnlocked = false
            visibleItemsCount = 0
            for (i in 1..items.size) {
                delay(120L)
                visibleItemsCount = i
            }
            delay(150L)
            isMenuExpansionUnlocked = true
        } else {
            isMenuExpansionUnlocked = false
            visibleItemsCount = 0
        }
    }

    Box(modifier = modifier.fillMaxSize()) {
        // Transparent scrim behind the menu to dismiss it when clicked outside
        if (visible) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null
                    ) { onScrimClick() }
            )
        }

        val menuOffset by animateDpAsState(
            targetValue = if (visible) 0.dp else (-220).dp,
            animationSpec = spring(
                dampingRatio = Spring.DampingRatioNoBouncy,
                stiffness = Spring.StiffnessMedium
            ),
            label = "menuOffset"
        )

        BoxWithConstraints(
            modifier = Modifier
                .fillMaxHeight()
                .width(220.dp)
                .offset(x = menuOffset)
                .align(Alignment.CenterStart)
                .pointerInput(Unit) {
                    awaitPointerEventScope {
                        while (true) {
                            awaitPointerEvent()
                            onInteraction()
                        }
                    }
                }
                .padding(start = 16.dp, top = 16.dp, bottom = 16.dp),
            contentAlignment = Alignment.CenterStart
        ) {
            val maxHeightPx = with(LocalDensity.current) { maxHeight.toPx() }
            val itemHeightPx = with(LocalDensity.current) { 60.dp.toPx() }
            val centerScrollOffset = -(maxHeightPx / 2 - itemHeightPx / 2).toInt()

            val centerOffset = 5000 - (5000 % items.size)
            val lazyListState = rememberLazyListState(
                initialFirstVisibleItemIndex = centerOffset,
                initialFirstVisibleItemScrollOffset = centerScrollOffset
            )
            val snappingLayout = remember(lazyListState) {
                SnapLayoutInfoProvider(lazyListState, SnapPosition.Center)
            }
            val snapFlingBehavior = rememberSnapFlingBehavior(snappingLayout)

            var isInitialScrollCompleted by remember { mutableStateOf(false) }

            LaunchedEffect(visible) {
                if (visible) {
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
                    val itemIndex = index % items.size
                    val item = items[itemIndex]
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

                            androidx.compose.foundation.layout.Row(
                                modifier = Modifier
                                    .width(boxWidth)
                                    .height(60.dp)
                                    .background(Color.Black, shape = RoundedCornerShape(12.dp))
                                    .border(2.dp, Color.White, shape = RoundedCornerShape(12.dp))
                                    .clickable { item.onClick() },
                                verticalAlignment = Alignment.CenterVertically
                            ) {
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
