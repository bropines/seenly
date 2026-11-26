package io.github.bropines.lens.app.data

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class SecondLanguageTest {
    private val ru = AppSettings(targetLanguage = "ru", secondLanguage = "en")

    @Test
    fun textInTheTargetLanguageGoesIntoTheSecond() {
        assertEquals("en", ru.swapTarget("ru"))
        assertEquals("en", ru.swapTarget("ru-RU"))
    }

    @Test
    fun otherTextKeepsTheTarget() {
        assertNull(ru.swapTarget("ja"))
        assertNull(ru.swapTarget(null))
    }

    @Test
    fun offOrAFixedSourceKeepsTheTarget() {
        assertNull(ru.copy(secondLanguage = null).swapTarget("ru"))
        assertNull(ru.copy(sourceLanguage = "ru").swapTarget("ru"))
        assertNull(ru.copy(secondLanguage = "ru").swapTarget("ru"))
    }

    @Test
    fun regionsMatter() {
        val zh = AppSettings(targetLanguage = "zh-CN", secondLanguage = "en")
        assertEquals("en", zh.swapTarget("zh"))
        assertNull(zh.swapTarget("zh-TW"))
    }
}
