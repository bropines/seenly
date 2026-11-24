# Changelog

All notable changes to Seenly are documented in this file. The project follows [Keep a Changelog](https://keepachangelog.com/en/1.1.0/) and [Semantic Versioning](https://semver.org/).

A release's notes on GitHub are its section here, under the highlights in `docs/releases/v<version>.md` when that file exists (`scripts/release/notes.py`). [CHANGELOG_RU.md](CHANGELOG_RU.md) is the Russian translation; both are edited together.

## [Unreleased]

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
