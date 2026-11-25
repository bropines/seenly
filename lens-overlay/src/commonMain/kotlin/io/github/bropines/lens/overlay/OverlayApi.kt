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
/** How many lines make a paragraph "dense" for [OverlayStyle.reflowDenseParagraphs]. */
public const val DENSE_PARAGRAPH_LINES: Int = 3

/** A layout refinement: decided per paragraph from its geometry, always, or never. */
public enum class LayoutChoice { Auto, On, Off }

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
     * comes back a few pixels tall once the page is shown on a phone: this is
     * the single biggest difference to legibility. A line raised past its box
     * gets a background sized to the measured text; lines that would then be
     * written through each other are re-wrapped instead ([reflowWhenCrowded]).
     * `0` turns the floor off.
     */
    val minFontSp: Float = 12f,
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
     * [TranslationLayout.Chromium] only: re-wrap a paragraph into one block
     * when setting it line by line would go wrong at the readable-size floor:
     * a bubble's column of short centred lines that only reaches the floor by
     * leaving its boxes, or any paragraph whose raised lines would overlap.
     * Without a floor nothing is raised and this changes nothing.
     */
    val reflowWhenCrowded: Boolean = true,
    /**
     * Re-wrapped text follows the bubble it stands in, read from the picture's
     * own pixels: each line as wide as the bubble is there. When no outline is
     * found, a paragraph known to be a bubble gets an ellipse and anything
     * else its rectangle.
     */
    val bubbleShape: Boolean = true,
    /**
     * Text that was one size in the source is one size in the translation.
     *
     * Within a paragraph set line by line, every line gets the same size, the
     * largest all of them have room for: Lens splits a paragraph's translation
     * over its lines unevenly, so sizing each line alone makes the size jump
     * from line to line. [LayoutChoice.Auto] does it where the source lines
     * are one size, On for every paragraph.
     *
     * Across the page (Auto and On alike, in both layouts), paragraphs whose
     * source type is one size and one colour are set at one size: bubbles
     * with bubbles, text with text. One cramped paragraph keeps a smaller
     * size of its own and does not set the page's. Nor does the readable
     * floor raise small print past what was larger in the source.
     *
     * Off is Chromium's behaviour: every line and paragraph on its own.
     */
    val uniformParagraphSize: LayoutChoice = LayoutChoice.Off,
    /**
     * [TranslationLayout.Chromium] only: how a line that needs more room than
     * its box is set.
     *
     * Off is Chromium's way: the line is shrunk to its box, and when the
     * readable floor raises it again it grows evenly to both sides over
     * whatever is there, on a background painted for it.
     *
     * On and Auto set each line at the size of the source's type and let it
     * run on past its box only into room the picture shows to be empty: no
     * other paragraph, nothing drawn, still inside the picture. It shrinks
     * when there is no such room, and the readable floor raises it on the
     * same terms, so lines are never written over each other or over an icon.
     * On grows a line from where it starts (the left for left-to-right text),
     * Auto from the side its paragraph is aligned to, and to both sides for
     * centred text. Two lines that face each other across a gap share it.
     */
    val growFromLineStart: LayoutChoice = LayoutChoice.Off,
    /**
     * Running text is set as one re-wrapped block in its paragraph's box
     * instead of line by line: at the size of the source's type, aligned as
     * the source was, its first line (and its indent) where the source's was
     * and the rest on the source's own lines. A translation that needs more
     * lines runs on below where the page is empty, and is otherwise set a
     * little smaller or leaded more tightly. Suits documents, screenshots and
     * photos of screens, where Lens's split of the translation over the lines
     * matters most.
     *
     * [LayoutChoice.Auto] does it for paragraphs that read as running text:
     * lines of one size with a common starting edge and ordinary spacing,
     * every line but the last full (the items of a list are not), and for
     * pages of vertical prose. In [TranslationLayout.Reflow] such paragraphs
     * are then not taken for bubbles. On does it, in
     * [TranslationLayout.Chromium], for every horizontal paragraph of
     * [DENSE_PARAGRAPH_LINES] or more lines.
     */
    val reflowDenseParagraphs: LayoutChoice = LayoutChoice.Off,
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
            // Manga is read at a glance; a floor fine for a shop sign is not.
            minFontSp = 14f,
            verticalText = VerticalTextPolicy.Horizontal,
            alignment = TextAlignment.Center,
            reflowGrowth = 1.45f,
            fillPadding = 0.45f,
        )
    }
}
