package io.github.bropines.lens.overlay

import androidx.compose.runtime.Immutable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import io.github.bropines.lens.protocol.TextAlignment

/** What the overlay shows on top of the image. */
public enum class OverlayMode {
    /** The image as it is. */
    Original,

    /** Translations painted over the source text, the way Chromium does. */
    Translated,

    /**
     * The text mode: the picture dimmed around the recognised text, every line
     * softly highlighted, and (with a [TextSelectionState]) words that can be
     * selected to copy or translate, as in Google Lens.
     */
    TextBoxes,
}

/** Where the translated text of a paragraph is set. */
public enum class TranslationLayout {
    /**
     * Each translated line is drawn in the box of the source line it replaces,
     * scaled down until it fits and never wrapped. This is what Chromium does,
     * and it is right for signs, screenshots and documents.
     */
    Chromium,

    /**
     * The whole translation is wrapped into the paragraph's box at one shared
     * size. This is the fix for speech bubbles, where the translation is much
     * longer than the Japanese it replaces and per-line fitting shrinks every
     * line to a different, tiny size.
     */
    Reflow,
}

/** How the source text is removed before the translation is drawn. */
public enum class EraseMode {
    /**
     * Paint the inpainted background patch Lens sends for each line, as
     * Chromium does. A line without a usable patch gets a flat fill instead.
     */
    ServerPatch,

    /**
     * Cover the paragraph's text area (the convex hull of its line boxes, grown
     * by [OverlayStyle.fillPadding]) with one flat colour: the background colour
     * Lens reports, or one sampled around the text when it reports none.
     * Cleaner than the patches wherever the background is flat, for example
     * inside a speech bubble, and wrong on photographs.
     */
    Fill,
}

/** What happens to top-to-bottom (vertical CJK) source text. */
public enum class VerticalTextPolicy {
    /** Stay vertical only when the translation is itself in a CJK language. */
    Auto,

    /** Always draw vertical columns, exactly like Chromium. */
    Keep,

    /** Always reflow the paragraph into horizontal wrapped lines. */
    Horizontal,
}

/**
 * How translations are drawn, independent of the [OverlayMode].
 *
 * The defaults reproduce Chromium's Lens overlay. [Manga] is a preset for pages
 * of speech bubbles.
 */
@Immutable
public data class OverlayStyle(
    val layout: TranslationLayout = TranslationLayout.Chromium,
    val erase: EraseMode = EraseMode.ServerPatch,
    /**
     * Multiplier on the outline drawn behind the text in the background colour,
     * which keeps it legible over whatever the erasing left behind. `0` removes
     * it, `1` is Chromium's hairline, larger values are a real stroke.
     */
    val outline: Float = 1f,
    /**
     * Smallest text size, in sp as displayed with the image fitted to the
     * view. Lens fits text to the source line, so fine print on a large page
     * can come back a few pixels tall. Raising it lets text leave its box, so
     * lines may overlap: `0` (the default) turns the floor off.
     */
    val minFontSp: Float = 0f,
    /** Multiplier on every fitted text size; below `1` leaves more air around the text. */
    val textScale: Float = 1f,
    val verticalText: VerticalTextPolicy = VerticalTextPolicy.Auto,
    /** The text alignment, or null to follow what Lens reports for the paragraph. */
    val alignment: TextAlignment? = null,
    /** The font, or null for the platform default (which falls back per script). */
    val fontFamily: FontFamily? = null,
    /**
     * [TranslationLayout.Reflow] only: how far the text area may grow past the
     * detected paragraph box, as a multiple of its size. The detected box hugs
     * the glyphs while a bubble has room around them. Growth stops at
     * neighbouring paragraphs and at the image's edges.
     */
    val reflowGrowth: Float = 1f,
    /** [EraseMode.Fill] only: how far the fill reaches past the text, in line thicknesses. */
    val fillPadding: Float = 0.3f,
    /**
     * The colour of text mode ([OverlayMode.TextBoxes]): a faint tint on every
     * line, a stronger one on the selection, and the selection handles at full
     * strength. Pass the theme's primary colour.
     */
    val highlightColor: Color = Color(0xFF4285F4),
) {
    public companion object {
        /** Chromium's own behaviour. */
        public val Chromium: OverlayStyle = OverlayStyle()

        /**
         * For manga and comics: reflow into the bubble, erase with a flat fill,
         * always horizontal, a visible outline and a readable minimum size.
         */
        public val Manga: OverlayStyle = OverlayStyle(
            layout = TranslationLayout.Reflow,
            erase = EraseMode.Fill,
            outline = 2f,
            minFontSp = 9f,
            verticalText = VerticalTextPolicy.Horizontal,
            alignment = TextAlignment.Center,
            reflowGrowth = 1.45f,
            fillPadding = 0.45f,
        )
    }
}
