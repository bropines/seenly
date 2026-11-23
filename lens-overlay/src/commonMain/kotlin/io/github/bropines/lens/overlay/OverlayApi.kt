package io.github.bropines.lens.overlay

/** What the overlay shows on top of the image. */
public enum class OverlayMode {
    /** The image as it is. */
    Original,

    /** Translations painted over the source text, the way Chromium does. */
    Translated,

    /** The recognised text boxes highlighted, for selecting and copying. */
    TextBoxes,
}
