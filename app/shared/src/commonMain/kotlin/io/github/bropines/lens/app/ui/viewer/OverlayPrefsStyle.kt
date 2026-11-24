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
