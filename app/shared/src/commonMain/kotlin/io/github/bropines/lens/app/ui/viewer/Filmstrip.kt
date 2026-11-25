package io.github.bropines.lens.app.ui.viewer

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.snapping.SnapPosition
import androidx.compose.foundation.gestures.snapping.rememberSnapFlingBehavior
import androidx.compose.foundation.interaction.collectIsDraggedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.background
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import io.github.bropines.lens.app.data.GalleryItem
import io.github.bropines.lens.app.ui.gallery.Thumbnail
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filterNotNull
import kotlin.math.abs

private val StripHeight = 64.dp
private val CellWidth = 36.dp
private val CellHeight = 48.dp
private val CellShape = RoundedCornerShape(8.dp)

/** The tray the thumbnails lie in: dark and translucent, so they read over a white page as over a dark one. */
private val Tray = Color.Black.copy(alpha = 0.5f)

/** The thumbnail nearest the middle of the strip, or null before it is laid out. */
private fun LazyListState.centered(): Int? {
    val info = layoutInfo
    val middle = (info.viewportStartOffset + info.viewportEndOffset) / 2
    return info.visibleItemsInfo.minByOrNull { abs(it.offset + it.size / 2 - middle) }?.index
}

/**
 * The gallery's pages as a strip of thumbnails with the page in front in the
 * middle. A tap goes to a page; dragging the strip scrubs through the pages,
 * the one under the middle showing as it passes, with a tick for each.
 *
 * @param onScrub the strip was dragged so that page [Int] is in the middle.
 * @param onScrubbing true from the touch until the strip has come to rest:
 *   pages passed meanwhile are looked at, not read.
 */
@Composable
fun Filmstrip(
    items: List<GalleryItem>,
    current: Int,
    failed: Set<Int>,
    onPick: (Int) -> Unit,
    onScrub: (Int) -> Unit,
    onScrubbing: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    val state = rememberLazyListState(initialFirstVisibleItemIndex = current.coerceIn(0, (items.size - 1).coerceAtLeast(0)))
    val haptics = LocalHapticFeedback.current
    val dragged by state.interactionSource.collectIsDraggedAsState()
    val front by rememberUpdatedState(current)
    var scrubbing by remember { mutableStateOf(false) }

    // In the user's hand from the touch until the fling it ends in has settled.
    LaunchedEffect(dragged, state.isScrollInProgress) {
        if (dragged) scrubbing = true else if (!state.isScrollInProgress) scrubbing = false
    }
    LaunchedEffect(scrubbing) { onScrubbing(scrubbing) }
    // The page turned some other way: its thumbnail comes to the middle.
    LaunchedEffect(current) { if (!scrubbing && state.centered() != current) state.animateScrollToItem(current) }
    LaunchedEffect(state) {
        snapshotFlow { if (scrubbing) state.centered() else null }
            .filterNotNull()
            .distinctUntilChanged()
            .collect { index ->
                if (index != front) {
                    haptics.performHapticFeedback(HapticFeedbackType.SegmentFrequentTick)
                    onScrub(index)
                }
            }
    }

    BoxWithConstraints(
        modifier.fillMaxWidth().padding(horizontal = 12.dp).height(StripHeight).clip(RoundedCornerShape(20.dp)).background(Tray),
    ) {
        // Padding of half the width less half a cell: every page, the first and last too, can stand in the middle.
        val side = ((maxWidth - CellWidth) / 2).coerceAtLeast(0.dp)
        LazyRow(
            state = state,
            contentPadding = PaddingValues(horizontal = side),
            horizontalArrangement = Arrangement.spacedBy(4.dp),
            verticalAlignment = Alignment.CenterVertically,
            flingBehavior = rememberSnapFlingBehavior(state, SnapPosition.Center),
            modifier = Modifier.fillMaxWidth().height(StripHeight),
        ) {
            items(items.size, key = { items[it].key }) { index ->
                val item = items[index]
                val isFront = index == current
                val number = "${index + 1}"
                Box(
                    Modifier
                        .size(CellWidth, CellHeight)
                        .graphicsLayer {
                            val scale = if (isFront) 1.16f else 1f
                            scaleX = scale
                            scaleY = scale
                            alpha = if (isFront) 1f else 0.72f
                        }
                        .clip(CellShape)
                        .then(if (isFront) Modifier.border(BorderStroke(2.dp, Color.White), CellShape) else Modifier)
                        .clickable { onPick(index) }
                        .semantics { contentDescription = number },
                ) {
                    Thumbnail(item.file, item.key, Modifier.size(CellWidth, CellHeight), shape = CellShape)
                    if (index in failed) {
                        Box(
                            Modifier.align(Alignment.TopEnd).size(10.dp).background(MaterialTheme.colorScheme.error, CircleShape),
                        )
                    }
                }
            }
        }
    }
}
