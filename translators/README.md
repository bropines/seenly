# translators

Text-translation engines behind one interface, and a pipeline that tries the
user's engines in order. Package `io.github.bropines.lens.translate`. The
facts about each service are in `docs/translators.md` and
`docs/google-translate.md`.

## Use

```kotlin
val pipeline = TranslationPipeline(
    registry = TranslatorRegistry(),            // the 13 built-in engines
    http = httpClient,                          // no automatic 429 retry, please
    settings = { TranslatorSettings(enabled = listOf("google", "bing", "deepl"), options = mapOf("deepl" to mapOf("api_key" to key))) },
    store = persistentStore,                    // DeepL (web) keeps its instance id here
    uiLanguage = { "ru" },
)
val result = pipeline.translate("Hello", target = "ru")
result.translation.text      // the translation
result.engineId              // who produced it
result.failures              // who failed or was skipped before that, and why
```

A provider can also be called on its own:
`GoogleProvider.translate(TranslationRequest("Hello", "ru"), SimpleProviderHost(httpClient))`.

Errors are one type, `TranslationException`, with an `ErrorCode`. Fail-over
stops at `MissingCredential`, `EmptyInput`, `SameLanguage` and cancellation;
everything else moves on to the next enabled engine.

## Engines

| id | service | key | limit per request |
|---|---|---|---|
| `google` | Google Translate, `/translate_a/single` | no | 10 000 |
| `bing` | Bing Translator web page | no | 1 000 |
| `yandex` | Yandex Translate web, with dictionary | no | 4 000 |
| `deepl-web` | DeepL iOS app endpoint | no | 1 500 |
| `lara` | Lara web | no | 5 000 |
| `modernmt` | ModernMT web | no | 5 000 |
| `mymemory` | MyMemory (source language required) | no, optional email | 500 |
| `lingva` | a Lingva instance | no | 5 000 |
| `deepl` | DeepL API, free (`:fx`) or Pro | yes | 10 000 |
| `deeplx` | your DeepLX server | optional token | 5 000 |
| `yandex-cloud` | Yandex Cloud Translate | yes | 10 000 |
| `libretranslate` | a LibreTranslate server | optional | 5 000 |
| `openai` | any chat-completions server | optional | 8 000 |

Keyless web endpoints are short-lived. What breaks first: Bing's session
parameters, the DeepL app version (an option of `deepl-web`, so it can be
updated without a release), public Lingva instances, MyMemory's quota.

## Adding an engine

Implement `TranslationProvider` and `register` it. The rules that keep it
testable and replaceable:

- No state in the provider. The HTTP client, the option values, the UI
  language and a string key-value store come in the `ProviderHost` of each
  call. Anything to remember between calls (a session, a device id, a bench)
  goes into `host.store`.
- Languages in and out are the internal BCP 47 tags of `Languages`; the
  engine's own codes live in its `LanguageMap`.
- Settings are declared as `OptionSpec`s; values are strings.
- Every failure is a `TranslationException`. A response that cannot be read
  is `BadResponse` ("the service changed its response"), never a crash.
- Test it with Ktor's `MockEngine` on a recorded response: see
  `KeylessProvidersTest`.

`LiveSmokeTest` calls the keyless engines once against the real services; it
only runs with `LENS_LIVE=1`.

## Scripted providers (not implemented)

Glossa ships its keyless engines as JavaScript modules that update without a
rebuild: a folder with `module.toml` (id, capabilities, languages,
`language_map`, `max_input_chars`, `permissions.network`, `[[options]]`) and a
`main.js` that talks one JSON protocol and sees the world only through a
synchronous `host.fetch / host.cache / host.log`. The interface here was
shaped so that such a module is just another `TranslationProvider`:

| module | this module |
|---|---|
| `module.toml` id, name, capabilities, `max_input_chars` | the same properties, read from the manifest |
| `languages` + `language_map` | `LanguageMap(supported, codes)`, built from data |
| `[[options]]` | `OptionSpec` (same six types, string values) |
| `host.fetch` | `ProviderHost.http`, wrapped to enforce `permissions.network` |
| `host.cache` | `ProviderHost.store` (string to string) |
| the protocol's error codes | `ErrorCode.fromId` (same wire names) |
| translate / detect / dictionary requests | `translate`, `detect`, `dictionary` |

So a later `ScriptedProvider(manifest, source, runtime) : TranslationProvider`
would, in `translate`: open a fresh QuickJS context, expose the three host
functions backed by the `ProviderHost` it was given, call the module with the
request as JSON, and turn the JSON answer into a `Translation` or a
`TranslationException`. It needs a QuickJS binding with JVM and Android
artifacts and a bridge from the module's synchronous `host.fetch` to Ktor's
suspend calls (run the script on a worker thread and block that thread, or use
the binding's async support). The registry, the pipeline, the settings page
and the tests need no change: `registry.register(scripted)` replaces a
built-in engine with the same id. New modules must be approved by the user
before they are registered, because a module sees the text.
