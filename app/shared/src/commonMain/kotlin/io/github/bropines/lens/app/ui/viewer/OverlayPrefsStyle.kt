package io.github.bropines.lens.app.ui.viewer

import androidx.compose.ui.text.font.FontFamily
import io.github.bropines.lens.app.data.EraseChoice
import io.github.bropines.lens.app.data.FontChoice
import io.github.bropines.lens.app.data.OutlineChoice
import io.github.bropines.lens.app.data.OverlayPrefs
import io.github.bropines.lens.app.data.RefineChoice
import io.github.bropines.lens.app.data.VerticalChoice
import io.github.bropines.lens.overlay.EraseMode
import io.github.bropines.lens.overlay.LayoutChoice
import io.github.bropines.lens.overlay.OverlayStyle
import io.github.bropines.lens.overlay.VerticalTextPolicy
import io.github.bropines.lens.app.data.AppSettings
import io.github.bropines.lens.app.data.ReadingLayout
import io.github.bropines.lens.protocol.ScanResult
import io.github.bropines.lens.protocol.WritingDirection

/**
 * The style [scan]'s page is drawn in: the preset its layout calls for, with
 * the user's overlay settings on top. One function for the viewer, the screen
 * translator and the preview in Settings, so all three draw alike.
 */
fun AppSettings.overlayStyle(scan: ScanResult?): OverlayStyle {
    val manga = when (readingLayout) {
        ReadingLayout.InPlace -> false
        ReadingLayout.Reflow -> true
        // Vertical source text means a comic page: bubbles want reflow, not per-column fitting.
        ReadingLayout.Auto -> scan?.paragraphs?.any { it.writingDirection == WritingDirection.TopToBottom } == true
    }
    // Lens misses tiny side columns (a lone "でも……"), so a reflow that grows far
    // runs over text it was never told about; grow a little less than the preset.
    val base = if (manga) OverlayStyle.Manga.copy(reflowGrowth = 1.25f) else OverlayStyle.Chromium
    return base.withPrefs(overlay)
}

/** The preset with the user's overlay settings on top; every `Auto` leaves the preset's value. */
fun OverlayStyle.withPrefs(prefs: OverlayPrefs): OverlayStyle = copy(
    erase = when (prefs.erase) {
        EraseChoice.Auto -> erase
        EraseChoice.Patch -> EraseMode.ServerPatch
        EraseChoice.Fill -> EraseMode.Fill
    },
    outline = when (prefs.outline) {
        OutlineChoice.Auto -> outline
        OutlineChoice.None -> 0f
        OutlineChoice.Thin -> 1f
        OutlineChoice.Bold -> 2.5f
    },
    textScale = prefs.textScale,
    minFontSp = if (prefs.minFontSp > 0) prefs.minFontSp.toFloat() else minFontSp,
    fontFamily = when (prefs.font) {
        FontChoice.System -> fontFamily
        FontChoice.Sans -> FontFamily.SansSerif
        FontChoice.Serif -> FontFamily.Serif
        FontChoice.Mono -> FontFamily.Monospace
    },
    verticalText = when (prefs.vertical) {
        VerticalChoice.Auto -> verticalText
        VerticalChoice.Keep -> VerticalTextPolicy.Keep
        VerticalChoice.Horizontal -> VerticalTextPolicy.Horizontal
    },
    uniformParagraphSize = prefs.uniformLineSize.toLayout(),
    growFromLineStart = prefs.growFromLineStart.toLayout(),
    reflowDenseParagraphs = prefs.reflowDense.toLayout(),
)

private fun RefineChoice.toLayout(): LayoutChoice = when (this) {
    RefineChoice.Auto -> LayoutChoice.Auto
    RefineChoice.On -> LayoutChoice.On
    RefineChoice.Off -> LayoutChoice.Off
}
