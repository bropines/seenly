# Changelog

All notable changes to Seenly are documented in this file. The project follows [Keep a Changelog](https://keepachangelog.com/en/1.1.0/) and [Semantic Versioning](https://semver.org/).

A release's notes on GitHub are its section here, under the highlights in `docs/releases/v<version>.md` when that file exists (`scripts/release/notes.py`). [CHANGELOG_RU.md](CHANGELOG_RU.md) is the Russian translation; both are edited together.

## [Unreleased]

### Added

- Colour presets in Settings → Appearance: from the wallpaper (Android 12+), violet, blue, teal, green, amber, red, pink or monochrome; the screen-translation menu follows them.
- AMOLED black: a pure black background in the dark theme.
- Three line-by-line refinements in Settings → Style, each Auto, On or Off: one text size per paragraph, widening a line from its aligned edge instead of both sides, and setting body-text paragraphs as one block. Auto decides for each paragraph from its shape (line sizes, a common edge, line spacing).

### Changed

- The Google account moved to Settings → Experimental and says what is known: Lens refuses cookie-signed requests (HTTP 401, it wants Chrome's own sign-in), so requests stay anonymous either way.

### Fixed

- Camera: the viewfinder showed a tighter frame than the photo it took; it now shows the whole 4:3 frame.
- Google account: when Google refuses the session, the app now shows Google's own reason on the account page and in the notice.

## [0.2.0] - 2026-11-25

### Added

- In-app updates: the app checks GitHub for a newer release on start (at most twice a day; can be turned off) and from Settings, shows the release notes, downloads the APK for your build, verifies it against the release's SHA256SUMS and hands it to the system installer. Betas are offered only if you ask for them.
- "What's new": after an update the app shows the changes of the new version once, in the interface language; it is also in Settings.
- The `google` build now has on-device recognition and translation (Google ML Kit): an automatic fallback when Lens cannot be reached or refuses, an "always on-device" mode in which images never leave the phone, and an "On-device (ML Kit)" text translator. Language models are downloaded on demand (over Wi-Fi only, if you wish) and can be deleted. A page scanned this way is marked, since it reads and translates less well than Lens.
- Optional Google account: sign in inside the app, or import cookies exported from a browser, and Lens requests go out signed in. The cookies are stored encrypted on the device, sent only to Google, and left out of a settings export unless you tick the box. If Google refuses the session, the app goes back to anonymous requests and says so once.

### Changed

- The `foss` build is checked on release to contain no ML Kit, Google Play services or Firebase code. Until now the two builds were the same.

## [0.1.0] - 2026-11-24

### Added

- Translate an image in place: share it into the app, open several from the gallery, or open a folder and swipe through it. Signs and screenshots are translated line by line; manga bubbles are re-flowed and shaped to the bubble.
- Tall images (webtoons, long screenshots) are scanned in full-resolution bands cut at plain rows, not through panels.
- Screen translation from a floating bubble over any app: This screen, Auto (translates whenever the picture stops moving and remembers screens it has already seen) and Area (inside a frame you place).
- Optional accessibility service: an opaque translation over the screen and no screen-recording consent; it reads no events and no window content.
- Text selection on the image: copy the original or the translation, translate or search the selection; export the translated picture.
- Visual search: circle something, or tap a dot Lens found.
- Text translators for typed and selected text — Google, Bing, Yandex, DeepL, LibreTranslate, OpenAI-compatible and more — in your order, with fail-over.
- Rotate a photo taken sideways; the rotated page is scanned again.
- Camera: tap to focus and meter, long press to lock, an exposure slider and a "screen" preset for photographing monitors.
- History, settings export and import (translator keys included), light and dark themes.
- English interface with a Russian translation; the app's language can be chosen apart from the system's.
