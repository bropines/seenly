# Seenly

Translate the text in any picture, in place: screenshots, photos, manga chapters, or whatever is on your screen right now.

Seenly is an Android app (with a desktop build) written with Kotlin Multiplatform and Compose Multiplatform. It reads text through the same Lens service that Chrome's built-in Lens overlay uses. It draws the translation over the original and erases what was there.

> Seenly is an independent project. It is not affiliated with, endorsed or supported by Google LLC. Google, Google Lens and Google Translate are trademarks of Google LLC, named here only to say which services the app talks to.

## What it does

- **Translate an image.** Share a picture into the app, open several from the gallery, or open a whole folder and swipe through it (made for manga chapters). The translation is drawn in place: line by line for signs and screenshots, re-flowed into the bubble for manga.
- **Translate the screen.** A floating bubble works over any app:
  - **This screen**: translates what is visible now.
  - **Auto**: translates by itself every time the picture stops moving.
  - **Area**: translates only inside a frame you place.

  With the optional accessibility service the translation is fully opaque and needs no screen-recording consent; the service reads no events and no window content.
- **Select and copy text** on the image, copy the original or the translation, export the translated picture.
- **Visual search**: circle something, or tap a dot Lens found, to search for it.
- **Text translators** for typed and selected text: Google, Bing, Yandex, DeepL, LibreTranslate, OpenAI-compatible and more, in your order, with fail-over.
- History, Material 3 Expressive design, light and dark themes, settings export and import.

## Modules

| Module | What it is |
|---|---|
| `lens-protocol` | Kotlin Multiplatform client for the Lens protocol: OCR, translation, background patches, visual search, tall-image tiling |
| `lens-overlay` | Renders a translated scan over the image (Compose), with text selection and zoom |
| `translators` | Pluggable text translation engines with a fail-over pipeline |
| `lens-cli` | Command-line client: `lens <image> -t en [--tiled] [--json]` |
| `app/shared`, `app/android`, `app/desktop` | The Seenly app |

## Building

You need JDK 21 and the Android SDK (compile SDK 37.1).

```bash
./gradlew :app:android:assembleGoogleDebug   # debug build, signed with the public dev key
./gradlew :app:android:assembleGoogleRelease # minified release
./gradlew :app:desktop:run                   # desktop app
./gradlew :lens-cli:installDist              # the CLI
```

The app comes in two flavors:
- `google`: may use Google Play services;
- `foss`: nothing proprietary on the device.

A release build is signed with the key described by these entries, read from `~/.gradle/gradle.properties` or the environment:

```
lens.release.storeFile=/path/to/key.jks
lens.release.storePassword=…
lens.release.keyAlias=…
lens.release.keyPassword=…
```

Without them, the release is signed with the public dev key in `app/android/dev.keystore`. That key is fine for testing and must never be used for a published release.

## Status

Early, but in daily use. Expect changes.
