package io.github.bropines.lens.translate

import io.github.bropines.lens.translate.providers.BingWebProvider
import io.github.bropines.lens.translate.providers.DeepLApiProvider
import io.github.bropines.lens.translate.providers.DeepLWebProvider
import io.github.bropines.lens.translate.providers.DeepLXProvider
import io.github.bropines.lens.translate.providers.GoogleProvider
import io.github.bropines.lens.translate.providers.LaraProvider
import io.github.bropines.lens.translate.providers.LibreTranslateProvider
import io.github.bropines.lens.translate.providers.LingvaProvider
import io.github.bropines.lens.translate.providers.ModernMtProvider
import io.github.bropines.lens.translate.providers.MyMemoryProvider
import io.github.bropines.lens.translate.providers.OpenAiCompatibleProvider
import io.github.bropines.lens.translate.providers.YandexCloudProvider
import io.github.bropines.lens.translate.providers.YandexWebProvider

/**
 * The engines the application knows, by id. Adding an engine is one
 * [register] call; nothing else in the module names concrete providers.
 */
public class TranslatorRegistry(providers: Iterable<TranslationProvider> = builtIn) {
    private val byId = LinkedHashMap<String, TranslationProvider>()

    init {
        providers.forEach(::register)
    }

    /** Adds [provider], replacing one with the same id (a plugin may override a built-in engine). */
    public fun register(provider: TranslationProvider) {
        byId[provider.id] = provider
    }

    public operator fun get(id: String): TranslationProvider? = byId[id]

    /** In registration order. */
    public val all: List<TranslationProvider> get() = byId.values.toList()

    public companion object {
        /** Keyless engines first, then the ones that need a key or a server. */
        public val builtIn: List<TranslationProvider> = listOf(
            GoogleProvider,
            BingWebProvider,
            YandexWebProvider,
            DeepLWebProvider,
            LaraProvider,
            ModernMtProvider,
            MyMemoryProvider,
            LingvaProvider,
            DeepLApiProvider,
            DeepLXProvider,
            YandexCloudProvider,
            LibreTranslateProvider,
            OpenAiCompatibleProvider,
        )
    }
}
