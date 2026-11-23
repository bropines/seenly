# Google Translate (text), as Glossa does it

What the Glossa session reported on 2026-11-23. Glossa's notes are dated
2026-11-07. Its source is `crates/glossa-services/src/google.rs` and `backoff.rs`, plus
`docs/research/google-translate.md`, in the Glossa repo on the Mac. Google
changes these endpoints without notice; treat this as a snapshot.

## Endpoint

Keyless `GET /translate_a/single`. Three hosts, tried in this order. The
response format is the same on all three.

1. `https://clients5.google.com/translate_a/single?client=dict-chrome-ex`
2. `https://translate.googleapis.com/translate_a/single?client=gtx`
3. `https://translate.google.<tld>/translate_a/single?client=gtx`. The tld comes
   from settings and defaults to `com`. Validate it as lowercase latin letters and
   dots, at most 16 chars. It goes last because a block on it is shared with the
   user's browser.

Google rate-limits each host and client id separately, and seems to fingerprint
the client including TLS. Glossa's rustls client got captchas on both gtx hosts
while curl did not. **Our OkHttp/Ktor fingerprint will differ: measure.**

Use GET while the url-encoded text is ≤1800 bytes. Longer text goes as a POST
to the same URL with the same query parameters and `q` in an
`application/x-www-form-urlencoded` body; otherwise the server returns 413 or
400.

Not used, and why:

- batchexecute (`MkEWBc`): needs page tokens.
- `client=t`/`webapp`: needs a computed `tk`.
- `translate-pa .../translateHtml`: needs the web API key.
- `/m`: returns HTML, and it hit captcha first.
- `/translate_a/t`: takes several `q` per request, but has no transliteration and
  no dictionary.

## Parameters

- `client`.
- `sl`: `auto` or a language code.
- `tl`: target language.
- `hl`: UI language; parts of speech come back named in it.
- `dt`: repeated. `t` translation, `bd` dictionary alternatives, `rm`
  transliteration, `ld` detection. A dictionary entry adds `md` definitions,
  `ex` examples and `ss` synonyms.
- `ie=UTF-8`, `oe=UTF-8`, `q`.

Headers: a browser User-Agent, `Accept: */*`, and `Accept-Language` set from the
UI language. No cookies, no key.

Language codes are Google's legacy ones: `iw`, `zh-CN`, `tl`, `no`.

## Response

The response is a positional JSON array, and which slots are filled depends on
`dt`. Read it only through safe indexing: a missing slot is null, never a crash.

- `[0]`: segments `[translation, original, …]`. The translated text is the
  concatenation of each segment's `[0]`. With `rm`, the last segment is
  `[null, null, translit_of_translation, translit_of_source]`; it is the one whose
  `[0]` is not a string. For an English source, `[3]` holds phonetics (həˈlō);
  never show it under the translation.
- `[1]`: dictionary entries `[pos, [words], [[word, [back-translations]], …]]`.
  Old responses have a plain word list here.
- `[2]`: source language, with `[8][0][0]` as the fallback. `und` means
  undetermined.
- `[6]`: detection confidence, with `[8][2][0]` as the fallback.
- `[11]`: synonyms. `[12]`: definitions. `[13]`: examples, as HTML with `<b>`;
  strip the tags.
- There is no separate detect call: send `sl=auto&tl=en&dt=ld` and read `[2]`
  and `[6]`.

## Failures

**Captcha.** It arrives as a 429, or as a redirect to `google.com/sorry`, which
can also come back as 200 or 403. To recognise it, the response must be HTML: a
`text/html` content type or a body starting with `<`. Then look for `/sorry/` or
`unusual traffic` in the first 64 KB. Never search a JSON body for those strings:
it echoes the source text, which may contain them.

**On captcha.** Move to the next host and don't retry the same one. Bench the
host that refused for 30 s, doubling on each consecutive refusal up to 10 min,
or for `Retry-After` if that is longer. A success resets the bench. Turn off the
HTTP client's automatic 429 retry for these requests: retries lengthen the
block.

**Other responses:**

- 403, a 5xx, or some other HTML page (such as the EU cookie consent page): move
  to the next host without benching it.
- A network error: move to the next host. A second network error in a row ends
  the attempt, since then the problem is the network, not the host.
- Any other 4xx, or an unreadable body from the first host: fail immediately.
- Every host benched: fail immediately with a "rate limited" error that reports
  the shortest wait.

## Long text

One POST has handled 144k chars. Glossa sends at most five chunks of 10k chars
each, one after another.

Where to cut a chunk, in order of preference:

1. After a newline in the second half of the window.
2. After the whitespace that follows `. ! ?`, or straight after `。！？`.
3. After any whitespace.
4. At the hard limit.

Google trims whitespace at both ends of each chunk's translation, so restore the
source's whitespace between chunks when joining them. Take `bd` from the first
chunk only.

## Speech (if needed)

`/translate_tts`, at most 200 chars per request. Parameters: `ie`, `tl`,
`total`, `idx`, `textlen`, `q`; add `ttsspeed=0.24` for slow speech. The
response is MP3, and chunks can simply be concatenated. The client depends on the
host:

- `tw-ob` on `translate.google.<tld>`
- `gtx` on googleapis
- `dict-chrome-ex` on clients5

Speech is rate-limited separately from translation.
