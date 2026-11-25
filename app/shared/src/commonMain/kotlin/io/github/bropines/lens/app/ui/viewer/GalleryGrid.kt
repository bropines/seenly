package io.github.bropines.lens.app.ui.viewer

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.bropines.lens.app.data.GalleryItem
import io.github.bropines.lens.app.resources.*
import io.github.bropines.lens.app.ui.gallery.BadgeMark
import io.github.bropines.lens.app.ui.gallery.HeroRegistry
import io.github.bropines.lens.app.ui.gallery.PageBadge
import io.github.bropines.lens.app.ui.gallery.Thumbnail
import io.github.bropines.lens.app.ui.gallery.heroSource
import org.jetbrains.compose.resources.pluralStringResource
import org.jetbrains.compose.resources.stringResource

/** The corner of a page's cell; the page flies out of and into this shape. */
internal val GridCellCorner = 18.dp
private val GridCellShape = RoundedCornerShape(GridCellCorner)

/**
 * Every picture of the gallery at a glance: a grid of thumbnails, each marked
 * when it is translated already or could not be read, the page being read
 * framed. A tap opens that page; the picture flies out of its cell.
 */
@Composable
fun GalleryGrid(
    title: String,
    items: List<GalleryItem>,
    current: Int,
    translatedCount: Int,
    badge: (Int) -> PageBadge?,
    state: LazyGridState,
    registry: HeroRegistry?,
    onOpen: (Int) -> Unit,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(color = MaterialTheme.colorScheme.surface, modifier = modifier.fillMaxSize()) {
        Column {
            TopAppBar(
                title = {
                    Column {
                        Text(title, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(
                            pluralStringResource(Res.plurals.gallery_images, items.size, items.size) +
                                if (translatedCount > 0) " · " + stringResource(Res.string.gallery_translated, translatedCount) else "",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                },
                navigationIcon = {
                    IconButton(onClick = onClose) { Icon(Icons.Rounded.Close, contentDescription = stringResource(Res.string.close)) }
                },
            )
            val bars = WindowInsets.navigationBars.asPaddingValues()
            LazyVerticalGrid(
                columns = GridCells.Adaptive(104.dp),
                state = state,
                contentPadding = PaddingValues(start = 12.dp, end = 12.dp, top = 4.dp, bottom = 16.dp + bars.calculateBottomPadding()),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.fillMaxSize(),
            ) {
                items(items.size, key = { items[it].key }) { index ->
                    val item = items[index]
                    val mark = badge(index)
                    val number = "${index + 1}"
                    Box(
                        Modifier
                            .aspectRatio(0.72f)
                            .clip(GridCellShape)
                            .then(
                                if (index == current) {
                                    Modifier.border(BorderStroke(3.dp, MaterialTheme.colorScheme.primary), GridCellShape)
                                } else {
                                    Modifier
                                },
                            )
                            .clickable { onOpen(index) }
                            .semantics { contentDescription = number },
                    ) {
                        Thumbnail(
                            item.file,
                            item.key,
                            Modifier.fillMaxSize().heroSource(registry, item.key),
                            shape = GridCellShape,
                        )
                        Text(
                            number,
                            style = MaterialTheme.typography.labelMedium,
                            color = Color.White,
                            modifier = Modifier
                                .align(Alignment.BottomStart)
                                .padding(6.dp)
                                .background(Color.Black.copy(alpha = 0.55f), MaterialTheme.shapes.small)
                                .padding(horizontal = 6.dp, vertical = 2.dp),
                        )
                        if (mark != null) BadgeMark(mark, Modifier.align(Alignment.TopEnd).padding(6.dp))
                    }
                }
            }
        }
    }
}
