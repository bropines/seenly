# lens-kmp: ресёрч и план первой версии

Черновик от 23.11.2026, до решений пользователя. Ничего не собрано.

## 1. Google Lens: что умеет и где проваливается

**Что умеет сейчас.** Перевод (в том числе живой камерой и с наложением на картинку), выделение,
копирование и озвучка текста, поиск по картинке и мультипоиск (картинка + текст), AI Overviews,
а в США и Индии ещё AI Mode, товары, места, растения и животные, QR. Circle to Search умеет
переводить на лету при прокрутке (с сентября 2025). Режим Homework убрали, его влили в общий поиск.

**Жалобы, подтверждённые несколькими источниками:**

1. **AI вместо выдачи.** Lens теперь толкует всю сцену через Gemini и начинает с AI-сводки, а
   похожие картинки приходится выпрашивать в чате. Если подвинуть рамку, можно попасть в AI Mode
   вместо поиска похожих. Обзоры бывают неверны, и выключить их нельзя.
   ([How-To Geek, 01.10.2026](https://www.howtogeek.com/google-lens-broke-google-decided-needed-to-think-for-you/),
   [Android Police](https://www.androidpolice.com/new-google-lens-ui-refresh/),
   [Full Fact](https://fullfact.org/technology/technologyinaccurate-google-ai-overviews/))
2. **Функции исчезают и переезжают.** Homework mode, ярлык на Pixel, иконка Lens в Circle to Search
   (её убрали в 2024-м, потом вернули).
   ([9to5Google](https://9to5google.com/2024/10/11/circle-to-search-google-lens/))
3. **Манга и вертикальный текст.** Столбцы читаются как строки, порядок справа налево ломается,
   пузыри склеиваются, SFX и стилизованные шрифты пропускаются. Пользовательский список таких проблем:
   [YomiNinja #46](https://github.com/matt-m-o/YomiNinja/issues/46). Остальные источники здесь
   продают конкурирующие инструменты: направление у них сходится, но цифрам верить нельзя.
4. **Копирование в неправильном порядке.** «Выделить всё» берёт CJK слева направо, люди выделяют
   вручную справа налево ([Adobe community](https://community.adobe.com/t5/acrobat-discussions/getting-errors-with-scan-of-japanese-text-printed-vertically-when-trying-to-convert-to-text/td-p/14337259)).
5. **Привязка к Google app и аккаунту, приватность.** На degoogled-телефонах Lens нет
   ([Privacy Guides](https://discuss.privacyguides.net/t/alternative-to-google-lens-for-camera-translation/17717)).
   История визуального поиска по умолчанию выключена и живёт только в My Activity аккаунта
   ([справка Google](https://support.google.com/websearch/answer/7539151)). Своей локальной истории
   переводов у Lens нет.
6. **Офлайн.** Языковые пакеты Lens у части людей не скачиваются
   ([PiunikaWeb, 2024](https://piunikaweb.com/2024/03/26/google-lens-cant-download-offline-languages-on-android/)).

**Чего не нашёл.** Reddit и отзывы Play недоступны ни скриптом, ни во встроенном браузере. Поэтому
жалобы на батч-обработку, экспорт, выбор исходного языка и тормоза камеры остаются гипотезами, а не
находками. Возможности протокола сверх OCR и перевода (визуальный поиск, товары, AI Mode)
исследует сессия userscript, результат будет в `docs/lens-capabilities.md` в её репо. Этого файла
пока нет.

### Что может значить «лучше Lens» (решает пользователь)

| # | Предложение | На какую жалобу отвечает |
|---|---|---|
| A | Сначала простой результат: текст, перевод, похожие картинки. AI только по кнопке | 1 |
| B | Наложение перевода качественнее, чем в Chromium: фон, который присылает сервер, режим манги (заливка пузыря, вертикальный текст, перенос строк), переключатель оригинал/перевод, тап по абзацу открывает оригинал рядом с переводом | 3 |
| C | Правильный порядок чтения (RTL, вертикальный CJK); копирование по слову, строке, абзацу и всему тексту; экспорт (txt/markdown) | 4 |
| D | Локальная история без аккаунта с полнотекстовым поиском по распознанному тексту; изображения уходят только на эндпоинт Lens; EXIF вычищается | 5 |
| E | Пачка картинок за раз, приём из «Поделиться», выбор языка OCR и исходного языка перевода | гипотезы |
| F | Десктопное приложение без Chrome: файл, буфер обмена, позже горячая клавиша на область экрана | Lens на десктопе есть только в Chrome |
| G | Живая камера: рамки текста сразу с устройства (ML Kit), по кадру запрос в Lens. Офлайн-фолбэк через ML Kit OCR и перевод | 6 |

## 2. Стек

Версии сверены с Maven Central, Google Maven и релиз-нотами 23.11.2026.

| Задача | Выбор | Почему / от чего отказался |
|---|---|---|
| Язык и сборка | Kotlin 2.4.21, Gradle 9.9.2, AGP 9.5.2, JDK 21 | Последние стабильные. Kotlin 2.5 пока RC |
| UI | Compose Multiplatform 1.13 (сейчас rc01), material3 1.13 | Material 3 Expressive стабилизирован только в androidx material3 1.5.0 (18.11.2026): ButtonGroup, SplitButton, FloatingToolbar, wavy-прогресс, MaterialShapes, expressive-тема. LoadingIndicator всё ещё experimental. У CMP стабильна лишь material3 1.9.0 (≈ androidx 1.4, без Expressive), а 1.10–1.12 были alpha. Поэтому сразу целюсь в 1.13 и перейду на стабильную, когда она выйдет. Это риск: недели на rc |
| Динамический цвет вне Android | materialkolor 5.0.1 | Тема из обоев или из картинки на десктопе |
| Protobuf | **Wire 7.2.2 + своя Gradle-задача, которая понижает editions** | См. ниже. `encodeInTagOrder = true` (новое в 7.2): байты как у protoc и Chromium |
| HTTP | Ktor 3.6.1: OkHttp на Android и JVM, Darwin на iOS | Единственный зрелый KMP-клиент. Протокол один POST, но Ktor даёт ретраи, таймауты, логирование и прокси из коробки |
| DI | Metro 1.6.0 | Граф проверяется при компиляции (как Dagger), KMP, без рефлексии. Отказался от Koin 4.2: проще, но ошибки ловит в рантайме. Цена Metro: плагин компилятора привязан к версии Kotlin |
| Навигация | Navigation 3 (JetBrains KMP 1.2.0) | Back stack — это просто список в моём состоянии, удобно для адаптивных list-detail. Отказался от Navigation Compose 2.10: старая модель графа |
| ViewModel/lifecycle | androidx lifecycle KMP 2.11.0 | Стандарт |
| Картинки | Coil 3.6.3 | Единственный полноценный KMP-загрузчик |
| БД | Room KMP 2.8.5 | Есть Flow, FTS4 для поиска по истории, поддержка Google. Отказался от SQLDelight 2.4.1: даёт полный контроль над SQL и FTS5, но больше ручной работы. Room 3 пока alpha |
| Настройки | DataStore 1.3.0 KMP | Стандарт |
| Логи | Kermit 2.2.0 | KMP, свои writers (Logcat, файл). kotlin-logging слабее за пределами JVM |
| Обработка изображений | expect/actual: Bitmap на Android, Skia (skiko) на десктопе и iOS | Готовой KMP-библиотеки ресайза и JPEG-кодирования нет. Skia уже внутри Compose, так что лишней зависимости не будет |
| Камера | CameraX 1.7.0 через expect/actual | KMP-обёртки камеры (CameraK, Peekaboo) молодые; живой режим всё равно Android-специфичен |
| OCR/перевод на устройстве (G) | ML Kit text-recognition 16.0.1, translate 17.0.3 | Только Android; модели можно встроить в APK без Play Services |
| Файлы и «Поделиться» | FileKit 0.17.1 | KMP: выбор, сохранение, шаринг |
| Тесты | kotlin.test + Kotest assertions 6.2.7 + Turbine 1.2.1 + Mokkery 3.6.0 | Mokkery работает на всех таргетах; MockK — только JVM |
| Стиль | Spotless 8.12 + ktlint; detekt 2.0 (alpha) | detekt 1.23.8 отстаёт от Kotlin 2.4 |

### Ловушка editions и Wire (проверено)

Wire — единственный зрелый генератор Kotlin-кода из protobuf для KMP. Google `protobuf-kotlin`
работает только на JVM. pbandk фактически заброшен. `kotlinx-serialization-protobuf` не генерирует
код из `.proto`. При этом парсер Wire на `edition` прямо бросает
`"edition is not currently supported"` (`ProtoParser.kt`), а
[issue #2546](https://github.com/square/wire/issues/2546) открыт.

Решение — то же, что в chrome-lens-rs, только на уровне текста `.proto`, потому что Wire читает
исходники, а не descriptor set:

- 27 из 44 файлов на editions (22 — 2023, 5 — 2024). Используются только `field_presence` и
  `enum_type = CLOSED`; синтаксиса 2024 (`export`, `local`, `import option`) нет.
- Файл с `option features.field_presence = IMPLICIT;` превращается в `syntax = "proto3"`,
  остальные в `"proto2"`. Строки `option features.*` удаляются.
- Per-field переопределения обрабатываются лучше, чем в rs: EXPLICIT внутри proto3-файла
  (`Word.text_separator`) становится `optional` в proto3, и отсутствие разделителя наконец
  отличимо от пустого. IMPLICIT внутри proto2 (`ImageCrop.crop_id`) остаётся `optional`, на
  проводе разницы нет. Как в rs, сборка падает на любом новом переопределении.
- Тонкость: в editions 2023 enum по умолчанию OPEN, а proto2 делает его closed. В Wire неизвестное
  значение enum в обоих случаях уходит в unknown fields, так что на разбор это не влияет. Тест это
  проверит.
- Проверка на проводе: тело запроса для одной и той же картинки побайтно сверяется с chrome-lens-rs
  (golden-тест), ответ разбирается на фикстурах.

## 3. Тулчейн в WSL

Бриф устарел: Debian теперь только оболочка. Android-проекты живут в дистрибутиве **`android`**
(Debian 13), и там уже есть всё нужное: Android SDK (platforms 34–37, build-tools до 36.1, NDK),
JDK 17 и 21 (sdkman), adb. Репо перенесено туда: `~/projects/lens-kmp`.

- **Ставить ничего не нужно.** Gradle 9.9.2 скачает wrapper, а недостающие build-tools AGP
  докачает сам, если лицензии приняты.
- **Почему не /mnt/f.** Замер на 2423 файлах: чтение 44 с через 9p против 0,03 с на ext4. SSD
  в порядке: из Windows те же файлы читаются за 0,4 с.
- **Телефон.** Проще всего беспроводная отладка: `adb pair` и `adb connect` из WSL. По USB
  нужен usbipd-win на Windows (ставить или нет — решает пользователь).
- **iOS** в WSL не собрать, нужен Mac. Можно добавить позже, если нужен.
- **Кэши.** Один `~/.gradle` в дистрибутиве; размер проверять `du -sh` в конце работы.

## 4. План первой версии (после согласования)

Модули:

```
build-logic/         convention-плагины, libs.versions.toml
lens-protocol/       KMP-библиотека: протобафы (Wire + понижение editions), запрос, разбор в
                     доменную модель (Page/Paragraph/Line/Word/Translation), Ktor-транспорт,
                     подготовка изображения (expect/actual)
lens-overlay/        раскладка перевода: подгонка шрифта по строке, вертикальный CJK, RTL,
                     режим манги; рисование на Compose Canvas, общее для всех платформ
app/shared/          Compose UI, Metro-граф, экраны, Room, DataStore
app/android/         Android-приложение
app/desktop/         JVM-приложение
```

Этапы, каждый заканчивается проверяемым результатом:

1. **Каркас.** Gradle и convention-плагины, пустые модули, сборка Android и desktop в `android`.
   Здесь же замер времени сборки.
2. **Протокол.** Вендорим протобафы (тот же коммит 0889dbff0b08), пишем задачу понижения editions,
   генерируем код Wire. Golden-тест запроса против chrome-lens-rs, тесты разбора (дыры в переводах
   по `deep_gleams`, UTF-16-диапазоны, `coordinate_type`). CLI на JVM: картинка → JSON.
3. **Наложение (B).** Слой рендера на Compose Canvas, режимы Chromium и манги.
4. **Приложение v1.** Открыть картинку (файл, «Поделиться», камера-снимок, буфер на десктопе) →
   OCR и перевод → наложение → выделение и копирование в правильном порядке → история с поиском.
   Material 3 Expressive.
5. **Дальше** по решению пользователя: живая камера и офлайн (G), визуальный поиск и AI (по
   `lens-capabilities.md`), захват области экрана на десктопе, iOS.

## Вопросы пользователю

1. Таргеты: Android + desktop сейчас, iOS потом (через Mac)?
2. Какие пункты A–G входят в v1?
3. Минимальный Android: предлагаю minSdk 26.
4. CMP 1.13 rc ради стабильного Expressive — ок?
5. Название приложения (сейчас рабочее «lens-kmp»).
