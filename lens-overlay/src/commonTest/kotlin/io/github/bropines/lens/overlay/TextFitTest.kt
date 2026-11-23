package io.github.bropines.lens.overlay

import io.kotest.matchers.shouldBe
import kotlin.test.Test

class TextFitTest {
    /** A stand-in for a font: every character is half an em wide, a line 1.2 em tall. */
    private fun fitsBox(text: String, boxWidth: Float, boxHeight: Float): (Float) -> Boolean = { size ->
        text.length * size * 0.5f < boxWidth && size * 1.2f < boxHeight
    }

    @Test
    fun `binary search finds the largest value that fits`() {
        largestFitting(1, 100) { it <= 37 } shouldBe 37
        largestFitting(1, 100) { true } shouldBe 100
        largestFitting(5, 5) { true } shouldBe 5
    }

    @Test
    fun `binary search probes logarithmically`() {
        var probes = 0
        largestFitting(1, 1000) { probes++; it <= 613 } shouldBe 613
        (probes <= 11) shouldBe true
    }

    @Test
    fun `nothing fitting falls back to the floor`() {
        largestFitting(3, 100) { false } shouldBe 3
    }

    @Test
    fun `a short line is limited by the box height`() {
        // 1.2 * size < 24 -> size < 20, and "equal" already fails.
        fitFontSize(3f, 24f, fits = fitsBox("Hi", 400f, 24f)) shouldBe 19.5f
    }

    @Test
    fun `a long line is limited by the box width`() {
        // 20 chars * 0.5 * size < 100 -> size < 10.
        fitFontSize(3f, 40f, fits = fitsBox("x".repeat(20), 100f, 40f)) shouldBe 9.5f
    }

    @Test
    fun `a line that cannot fit comes back at the minimum`() {
        fitFontSize(3f, 40f, fits = fitsBox("x".repeat(500), 100f, 40f)) shouldBe 3f
    }

    @Test
    fun `the search never exceeds its maximum`() {
        fitFontSize(3f, 12f, fits = { true }) shouldBe 12f
        // A maximum below the minimum still yields the minimum.
        fitFontSize(3f, 1f, fits = { true }) shouldBe 3f
    }

    @Test
    fun `scale and readable floor apply after fitting`() {
        finalFontSize(20f, textScale = 1f, minFontPx = 0f) shouldBe 20f
        finalFontSize(20f, textScale = 0.5f, minFontPx = 0f) shouldBe 10f
        finalFontSize(6f, textScale = 1f, minFontPx = 14f) shouldBe 14f
        finalFontSize(1f, textScale = 1f, minFontPx = 0f) shouldBe MIN_FONT_PX
    }

    @Test
    fun `vertical text stays vertical only where it reads well`() {
        keepsVertical(VerticalTextPolicy.Keep, "en") shouldBe true
        keepsVertical(VerticalTextPolicy.Horizontal, "ja") shouldBe false
        keepsVertical(VerticalTextPolicy.Auto, "ja") shouldBe true
        keepsVertical(VerticalTextPolicy.Auto, "zh-TW") shouldBe true
        keepsVertical(VerticalTextPolicy.Auto, "ru") shouldBe false
        keepsVertical(VerticalTextPolicy.Auto, null) shouldBe false
    }

    @Test
    fun `right to left languages are recognised by base tag`() {
        isRtlLanguage("ar") shouldBe true
        isRtlLanguage("he-IL") shouldBe true
        isRtlLanguage("en") shouldBe false
        isRtlLanguage(null) shouldBe false
    }

    @Test
    fun `a column splits into upright and sideways runs`() {
        verticalRuns("日本語ABC漢字") shouldBe listOf(
            VerticalRun(true, "日本語"),
            VerticalRun(false, "ABC"),
            VerticalRun(true, "漢字"),
        )
    }

    @Test
    fun `whitespace joins the run before it`() {
        verticalRuns("日 本") shouldBe listOf(VerticalRun(true, "日 本"))
        verticalRuns("ab cd") shouldBe listOf(VerticalRun(false, "ab cd"))
        verticalRuns("") shouldBe emptyList()
    }

    @Test
    fun `surrogate pairs stay whole`() {
        // U+20BB7, a CJK Extension B ideograph, is two UTF-16 units.
        val rare = "𠮷"
        codePointStrings("a${rare}b") shouldBe listOf("a", rare, "b")
        codePointOf(rare) shouldBe 0x20BB7
        verticalRuns("${rare}x") shouldBe listOf(VerticalRun(true, rare), VerticalRun(false, "x"))
    }
}
