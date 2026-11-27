package io.github.bropines.lens.app.ui.components

import androidx.compose.foundation.layout.BoxWithConstraintsScope
import androidx.compose.ui.unit.dp

/**
 * Room for things side by side rather than stacked: wider than tall and wide enough for two
 * panes, as a phone turned on its side or a tablet held the long way.
 */
fun BoxWithConstraintsScope.isWide(): Boolean = maxWidth > maxHeight && maxWidth >= 600.dp
