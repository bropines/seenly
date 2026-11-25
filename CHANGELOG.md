# Changelog

All notable changes to Seenly are documented in this file. The project follows [Keep a Changelog](https://keepachangelog.com/en/1.1.0/) and [Semantic Versioning](https://semver.org/).

A release's notes on GitHub are its section here, under the highlights in `docs/releases/v<version>.md` when that file exists (`scripts/release/notes.py`). [CHANGELOG_RU.md](CHANGELOG_RU.md) is the Russian translation; both are edited together.

## [Unreleased]

## [0.4.0] - 2026-11-25

### Added

- A proxy for the app's own requests, in Settings → Network: none, the system's, HTTP or SOCKS5, with an optional user name and password and a "Test connection" button that makes one small request through what is on screen and reports the time or the error. It covers Lens scans and search, the text translators and the updater, and takes effect without a restart. Not covered, and the page says so: Google's sign-in page inside the app, on-device model downloads and links opened in the browser. A proxy that is set but cannot be reached fails requests instead of letting them out directly. The password is kept with the other secrets (encrypted on Android) and goes into a settings export only when asked.
- A live preview on the overlay page: a made-up sign and a manga panel drawn with the current layout and style, by the same code as real pages.
- Viewer gallery: a grid of a folder's or a set's pictures with marks for translated and failed pages, and a strip of thumbnails under the picture to jump or scrub through pages.
- Pull a picture down to put it away: it follows the finger and lands in its thumbnail. The system back gesture draws it back with its progress.
- Hold a picture for quick actions (copy, share, save, translate again); hold the eye to peek at the original.
- The viewer's panel folds down to one row (languages, rotate, the eye) with the arrow beside the eye, and stays folded until unfolded. With every control hidden by a tap, a small see-through eye stays in the corner to switch the translation on and off.
- Share the translated picture, the text, or both; save the translated picture to the gallery.
- Pages read before open without a request, also offline. The next pages of a folder are translated ahead, and a folder reopens at the page it was left at.
- Screen translation: copy the translated text or the original from the bubble menu.
- Screen translation: the bubble tucks itself against the screen edge when left alone and steps out of the picture while "This screen" captures, so text under it is translated too.
- Screen translation: a notification with a way back when the system ends the screen capture or the accessibility service is switched off; with the accessibility service, the bubble returns by itself after the system kills the app.

### Changed

- Settings is no longer one long list. The tab opens on a card that says what Seenly is set to do (languages, layout, theme, AMOLED, on-device mode, proxy, colour) and changes most of it in place, over a grid of tiles for Translation, Overlay & style, Screen translation, Appearance, Network, Updates, App and Experimental; each tile shows what is set inside and opens its own page. Choices between a few values are chips instead of radio lists, and every page has its current state under the title.
- Documents, screenshots and photos of screens are typeset as a page: running text is re-wrapped in its own paragraph on the original's lines, with its alignment and indent, and text that was one size in the original is one size in the translation.
- A translated line that needs more room runs on only into space the picture shows to be empty; lines are no longer enlarged over neighbouring text, icons or the picture's edge.
- Manga: columns of one bubble that Lens returns as separate pieces are put back together; text keeps clear of small side lettering Lens did not read and of the bubble's outline, and bubbles on a page share one size. Erasing in a bubble uses the bubble's own colour and stops at its outline.
- Long strips (webtoons, long screenshots) are shown at the screen's width, sharp, and scrolled.
- "Copy" and "All text" follow the reading order of a manga page.
- Auto and Area: the translation is hidden the moment a finger touches the screen, and after a scroll the lines already translated go back up at once where they are now, while the rest is being translated.
- Auto and Area: parts of the screen that never hold still (a spinner, a GIF, a video in a corner) no longer block or hide the translation.
- Auto and Area use less battery: nothing is captured while paused, in the menu or with the screen off, and a still screen is looked at less often.
- The area remembers its place separately for portrait and landscape.

### Fixed

- Auto with the accessibility service sent a request for every frame of a scroll and then waited up to three seconds before translating the page it stopped on.
- With no connection, Auto showed a "couldn't translate" message over and over; it is shown once, and the bubble turns red.
- With screen recording, Auto could do nothing after un-pausing or closing the menu until the screen moved.
- A picture shared while another was open showed the previous one.
- Memory grew with every page of a long chapter.
- A page could stay on "Recognising text…" for good after a cancelled request.

## [0.3.0] - 2026-11-25

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
