# Translation engines, as Glossa does them

What the Glossa session reported on 2026-11-23. It was read from Glossa's code,
not called live. The Glossa repo is at `/Users/pinus/projects/glossa/glossa` on
the Mac. Keyless web endpoints change without notice. Google Translate itself is
in `google-translate.md`.

## Without a key

**Bing (web)**, `crates/glossa-services/src/bing.rs`

- Request:
  1. `GET https://www.bing.com/translator`. Take `IG` (after `IG:"`) from the
     HTML, `key` and `token` from `params_AbusePreventionHelper = [key, "token", …]`,
     and the cookies from Set-Cookie.
  2. `POST https://www.bing.com/ttranslatev3?isVertical=1&&IG=<ig>&IID=translator.5027`.
     The form fields are `fromLang` (`auto-detect`), `to`, `text`, `token`,
     `key` and `tryFetchingGenderDebiasedTranslations=true`. Send Origin
     `https://www.bing.com`, the page as Referer, and the Cookie.
- Response: an array holding `[0].translations[0].text`,
  `.transliteration.text` and `[0].detectedLanguage.{language,score}`. An object
  with `statusCode` instead means an error (429 is rate limiting).
- Session: keep it for 20 minutes. On an error, reload the page once and retry.
- Limits: about 1000 characters per request.
- Language codes: BCP 47, plus `sr-Cyrl`, `mn-Cyrl` and `mww`. There is no `be`,
  `eo`, `la`, `yi` or `tg`.
- The anonymous Edge token endpoint has been dead since 2026.

**Yandex (web)**, `modules/bundled/yandex-web/main.js`

- Every request carries the client name `srv=browser_video_translation`. Without
  it the server answers 410.
- Translate: `POST https://translate.yandex.net/api/v1/tr.json/translate?srv=…&lang=<from>-<to>`
  (with only `<to>`, the source is detected), form `text=…`, Referer
  `https://translate.yandex.com/`. The response is `{code:200, lang, text:[…]}`.
- Detect: `GET /api/v1/tr.json/detect?srv=…&text=<first 256 chars>`.
- Dictionary: `GET /dicservice.json/lookup?srv=…&lang=<from>-<to>&text=…` returns
  `def[]` with `pos`, `ts` and `tr[]` (`text`, `syn`, `mean`, `ex`). Use it for
  1–3 words of at most 60 characters.
- Errors also arrive with HTTP 200: check `code` (413 too long, 501 pair not
  supported, 429).
- Limits: chunk at 4000 characters and restore the whitespace.
- Language codes: `zh` for both Chinese scripts, `tl`, and `no`.

**DeepL (web)**, `modules/bundled/deepl-web/main.js`

- Request: `POST https://oneshot-free.www.deepl.com/v1/translate`, the free
  endpoint of the iOS app, with a JSON body:
  - `text:[…]`, `target_lang`, and `source_lang` (omit it to detect);
  - `usage_type:"translate"`;
  - `app_information:{os:"iOS", os_version, app_version, app_build, instance_id}`,
    where `instance_id` is a UUID v4 created once and kept.
- User-Agent: `DeepL/<version> (com.linguee.DeepLMobileTranslator; build:<build>; iOS <os>)`.
  When the server starts answering 403, update the version to the current App
  Store release.
- Response: `{translations:[{text, detected_source_language}]}`.
- Language codes: targets need a variant (`en-US`/`en-GB`, `pt-BR`/`pt-PT`,
  `zh-Hans`/`zh-Hant`); a Chinese source is just `zh`.
- Limits: 1500 characters.
- Errors: 429 comes with Retry-After, 451 means the region is not served, 403 is
  a refusal.

**Lara**, `modules/bundled/lara/main.js`

- Request: `POST https://webapi.laratranslate.com/translate` with JSON
  `{q, source:"" (detect) | code, target, instructions:[]}`. Send
  Origin/Referer `https://laratranslate.com`.
- Response: `{status, content:{translation, source_language}}`.
- Errors: `{error:{type,message}}`; `UnsupportedLanguageError` means the
  language is not supported.
- Language codes: BCP 47.
- Limits: 5000 characters.

**ModernMT**, `modules/bundled/modernmt/main.js`

- Request: `POST https://www.modernmt.com/translate?_data=routes%2Ftranslate`, form
  `text`, `targetLanguage`, `sourceLanguage` (omit it to detect). Send the site's
  Origin and Referer.
- Response: `{status:"success", value:{translation, detectedLanguage}}`.
- Language codes: `zh-CN`, `zh-TW`, `tl`.
- Limits: 5000 characters.

**MyMemory**, `crates/glossa-services/src/mymemory.rs`

- Request: `GET https://api.mymemory.translated.net/get?q=…&langpair=<from>|<to>[&de=<email>]`.
  An email raises the quota from 5k to 50k characters a day. It is personal data,
  so send it only if the user opts in.
- Response: always HTTP 200. The real status is in `responseStatus`, the
  translation in `responseData.translatedText`.
- Detection: none. The source language must be given.
- Limits: 500 characters.
- Gotcha: when the quota runs out, the "translation" is a "MYMEMORY WARNING"
  text, or `quotaFinished=true`. Never show that as a translation.

**Lingva**, `crates/glossa-services/src/lingva.rs`

- Request: `GET <instance>/api/v1/<from>/<to>/<urlencoded text>`. The default
  instance is `https://lingva.ml`.
- Response: `{translation, info:{detectedSource, pronunciation, extraTranslations}}`.
- Language codes: Google's legacy codes, plus `zh` and `zh_HANT`.
- Limits: 5000 characters.
- Gotcha: an instance that has lost its access to Google echoes the input. Treat
  output equal to the input as an error when the input has 3 or more words and
  the source is not the target.

## With a key, or a server of your own

**DeepL API**, `crates/glossa-services/src/deepl.rs`

- Request: `POST {api-free.deepl.com | api.deepl.com}/v2/translate`; a key
  ending in `:fx` goes to `api-free`. Send `Authorization: DeepL-Auth-Key <key>`
  and JSON `{text:[…], target_lang, source_lang?, formality?}`.
- Language codes: upper case. The source is `ZH`; targets are
  `ZH-HANS`/`ZH-HANT`, `EN-US`/`EN-GB`, `PT-BR`/`PT-PT`.
- `formality`: only for DE, FR, IT, ES, NL, PL, PT-BR, PT-PT, JA and RU.
- Errors: 403 is a bad key, 456 means the quota is used up.

**DeepLX** (your own server), `crates/glossa-services/src/deeplx.rs`

- Request: `POST <url>`, by default `http://127.0.0.1:1188/translate`, with
  `{text, target_lang, source_lang?}` and an optional Bearer token.
- Response: HTTP 200 with its own `code`; check both. The translation is in
  `data`, plus `alternatives`.

**Yandex Cloud**, `crates/glossa-services/src/yandex.rs`

- Request: `POST https://translate.api.cloud.yandex.net/translate/v2/translate`
  with `{texts, targetLanguageCode, sourceLanguageCode?, folderId?}`.
- Auth: `Authorization: Api-Key <key>`. An IAM token (`t1.…`) goes as `Bearer`
  and then needs `folderId`.
- Detection: `/detect`.

**LibreTranslate**, `crates/glossa-services/src/libretranslate.rs`

- Request: `POST <url>/translate` with
  `{q, source:"auto"|code, target, format:"text", api_key?}`.
- Response: `translatedText`, `detectedLanguage.language`, `alternatives`.
- Language codes: `zh`, `zt` (Traditional), `tl`; `pb` (Portuguese) appears in
  responses.
- Errors: 400 or 403 mentioning "api key" means the instance requires a key.

**OpenAI-compatible LLM**, `crates/glossa-services/src/openai.rs`

- Request: chat-completions; the base URL and model come from settings.
- Prompt: the system prompt carries `{from}`/`{to}`. The text goes in its own
  user message and is never pasted into the instructions.
- Defaults: SSE streaming, temperature 0.2, 120 s timeout.

## Failures and retries

- Captcha: none of these engines has one; Google is the only one. Refusal is
  429, in the status or in the body.
- Retries: 1 by default, after a connection error or 429/500/502/503/504. The
  pause is about 0.6 s, doubling, with 50–100 % jitter. A Retry-After of up to
  5 s is honoured; a longer one stops retrying.
- Timeouts: a timeout is not retried; move on to the next engine. The
  per-provider timeout is 15 s, out of a 30 s budget per call.
- What breaks most often:
  - Bing's session parameters;
  - deepl-web's app version;
  - public Lingva instances;
  - MyMemory's quota.

  Write parsers so that a changed response gives the error "the service changed
  its response", never a crash.

## Abstraction (Glossa)

**Provider.** `provider.rs` defines `info, options, translate, translate_stream,
detect, dictionary, speak, recognize, web_link, is_local, suspend, warm_up`; each
defaults to "unsupported". `ProviderInfo` holds:

- id, name, description;
- capabilities;
- languages (with or without auto);
- `max_input_chars`;
- `ready_to_use`, `needs_credentials`.

**Host.** A host is passed into every call: the HTTP client, the provider's
setting values, the UI language and a cancel flag. A provider touches no global
state, so it is tested against recorded responses.

**Settings.** They are declarative: `OptionSpec` gives key, type
(text/secret/url/select/boolean/number), default, hint, required and choices. The
settings page is built from them.

**Languages.** An internal BCP 47 table, plus a code map for each provider.

**Pipeline** (`pipeline.rs`):

- Candidates are the requested engine, then, if `fail_over` is on, the other
  enabled engines in the user's order. An engine the user has not enabled never
  sees the text.
- A candidate that does not know the target language is skipped. One that cannot
  detect gets the detected language.
- Move on after any error except a settings error, empty input, identical
  languages or cancellation. An empty translation counts as an error.
- The result lists who failed and why.

**Long text.** Chunking happens in the engine, by `max_input_chars`, with the
whitespace between chunks restored. The source language is fixed after the first
chunk.

**Errors.** One set of codes: `rate_limited(retry_after)`, `http`,
`bad_response`, `source_language`, `target_language`, `bad_request`,
`not_found`, `missing_credential`, `network`, `timeout`, `cancelled`.

**Plugins.** A plugin is a folder with `module.toml`:

- id, runtime, capabilities, languages, `language_map`, `max_input_chars`;
- `permissions.network`, a host allow-list;
- `[[options]]`.

It talks one JSON protocol (`docs/modules/protocol.md`). The runtimes are
JavaScript (QuickJS, a fresh context per call, synchronous
`host.fetch/cache/log`), any process over stdin/stdout, and a native C ABI. A new
module must be approved. Templates: github.com/bropines/glossa-module-kit.

**Lesson.** Keyless web endpoints are short-lived. Glossa ships them as JS
modules that update without a rebuild, and keeps the stable APIs in the core.
