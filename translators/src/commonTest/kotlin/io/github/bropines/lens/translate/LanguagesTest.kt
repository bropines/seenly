package io.github.bropines.lens.translate

import io.github.bropines.lens.translate.providers.BingWebProvider
import io.github.bropines.lens.translate.providers.DeepLApiProvider
import io.github.bropines.lens.translate.providers.DeepLWebProvider
import io.github.bropines.lens.translate.providers.GoogleProvider
import io.github.bropines.lens.translate.providers.LibreTranslateProvider
import io.github.bropines.lens.translate.providers.LingvaProvider
import io.github.bropines.lens.translate.providers.ModernMtProvider
import io.github.bropines.lens.translate.providers.YandexWebProvider
import io.kotest.matchers.shouldBe
import kotlin.test.Test

class LanguagesTest {
    @Test
    fun normalizeAcceptsEverySpelling() {
        Languages.normalize("zh_CN") shouldBe "zh-Hans"
        Languages.normalize("zh") shouldBe "zh-Hans"
        Languages.normalize("zh-TW") shouldBe "zh-Hant"
        Languages.normalize("zh_HANT") shouldBe "zh-Hant"
        Languages.normalize("iw") shouldBe "he"
        Languages.normalize("tl") shouldBe "fil"
        Languages.normalize("no") shouldBe "nb"
        Languages.normalize("PT-br") shouldBe "pt-BR"
        Languages.normalize("sr-cyrl") shouldBe "sr-Cyrl"
        Languages.name("pt-BR") shouldBe "Portuguese (Brazil)"
        Languages.name("zh-CN") shouldBe "Chinese (Simplified)"
    }

    @Test
    fun googleUsesItsLegacyCodes() {
        val map = GoogleProvider.languages
        map.toProvider("he", target = true) shouldBe "iw"
        map.toProvider("zh-Hans", target = true) shouldBe "zh-CN"
        map.toProvider("zh-TW", target = true) shouldBe "zh-TW"
        map.toProvider("fil", target = false) shouldBe "tl"
        map.toProvider("nb", target = true) shouldBe "no"
        // A variant Google does not tell apart falls back to the base language.
        map.toProvider("en-GB", target = true) shouldBe "en"
        map.fromProvider("iw") shouldBe "he"
        map.fromProvider("zh-CN") shouldBe "zh-Hans"
        map.supportsTarget("xx") shouldBe false
    }

    @Test
    fun deepLWantsAVariantForATargetAndABareSource() {
        val web = DeepLWebProvider.languages
        web.toProvider("en", target = true) shouldBe "en-US"
        web.toProvider("en-GB", target = true) shouldBe "en-GB"
        web.toProvider("pt", target = true) shouldBe "pt-BR"
        web.toProvider("zh-Hant", target = true) shouldBe "zh-Hant"
        web.toProvider("zh-Hant", target = false) shouldBe "zh"
        web.toProvider("en-GB", target = false) shouldBe "en"
        web.supportsTarget("be") shouldBe false

        val api = DeepLApiProvider.languages
        api.toProvider("zh-Hans", target = true) shouldBe "ZH-HANS"
        api.toProvider("zh-Hans", target = false) shouldBe "ZH"
        api.toProvider("pt-PT", target = true) shouldBe "PT-PT"
        api.fromProvider("ZH") shouldBe "zh-Hans"
        api.fromProvider("EN") shouldBe "en"
        api.fromProvider("NB") shouldBe "nb"
    }

    @Test
    fun bingHasItsOwnScriptsAndGaps() {
        val map = BingWebProvider.languages
        map.toProvider("sr", target = true) shouldBe "sr-Cyrl"
        map.toProvider("hmn", target = true) shouldBe "mww"
        map.toProvider("zh-CN", target = true) shouldBe "zh-Hans"
        map.fromProvider("mn-Cyrl") shouldBe "mn"
        for (missing in listOf("be", "eo", "la", "yi", "tg")) map.supportsTarget(missing) shouldBe false
    }

    @Test
    fun theOtherEnginesCodes() {
        YandexWebProvider.languages.toProvider("zh-Hant", target = true) shouldBe "zh"
        YandexWebProvider.languages.fromProvider("zh") shouldBe "zh-Hans"
        YandexWebProvider.languages.toProvider("nb", target = true) shouldBe "no"
        ModernMtProvider.languages.toProvider("zh-Hant", target = true) shouldBe "zh-TW"
        ModernMtProvider.languages.toProvider("fil", target = true) shouldBe "tl"
        LingvaProvider.languages.toProvider("zh-Hant", target = true) shouldBe "zh_HANT"
        LingvaProvider.languages.toProvider("zh-Hans", target = true) shouldBe "zh"
        LibreTranslateProvider.languages.toProvider("zh-Hant", target = true) shouldBe "zt"
        LibreTranslateProvider.languages.fromProvider("pb") shouldBe "pt-BR"
        LibreTranslateProvider.languages.fromProvider("zt") shouldBe "zh-Hant"
    }

    @Test
    fun everyBuiltInEngineIsRegisteredOnceAndKnowsEnglish() {
        val all = TranslatorRegistry().all
        all.map { it.id }.distinct().size shouldBe all.size
        all.size shouldBe 13
        for (provider in all) {
            provider.languages.supportsTarget("en") shouldBe true
            provider.options.map { it.key }.distinct().size shouldBe provider.options.size
            // An engine that needs credentials must say which option holds them.
            if (provider.needsCredentials) provider.options.any { it.required } shouldBe true
        }
    }
}
