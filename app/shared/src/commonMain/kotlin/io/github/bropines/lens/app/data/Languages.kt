package io.github.bropines.lens.app.data

/** A language Lens and Google Translate accept, by Google's code, with its own name. */
data class Language(val code: String, val nativeName: String, val englishName: String)

object Languages {
    val all: List<Language> = listOf(
        Language("ru", "Русский", "Russian"),
        Language("en", "English", "English"),
        Language("ja", "日本語", "Japanese"),
        Language("ko", "한국어", "Korean"),
        Language("zh-CN", "中文（简体）", "Chinese (Simplified)"),
        Language("zh-TW", "中文（繁體）", "Chinese (Traditional)"),
        Language("uk", "Українська", "Ukrainian"),
        Language("be", "Беларуская", "Belarusian"),
        Language("kk", "Қазақ", "Kazakh"),
        Language("de", "Deutsch", "German"),
        Language("fr", "Français", "French"),
        Language("es", "Español", "Spanish"),
        Language("it", "Italiano", "Italian"),
        Language("pt", "Português", "Portuguese"),
        Language("pl", "Polski", "Polish"),
        Language("cs", "Čeština", "Czech"),
        Language("nl", "Nederlands", "Dutch"),
        Language("sv", "Svenska", "Swedish"),
        Language("fi", "Suomi", "Finnish"),
        Language("no", "Norsk", "Norwegian"),
        Language("da", "Dansk", "Danish"),
        Language("tr", "Türkçe", "Turkish"),
        Language("el", "Ελληνικά", "Greek"),
        Language("iw", "עברית", "Hebrew"),
        Language("ar", "العربية", "Arabic"),
        Language("fa", "فارسی", "Persian"),
        Language("hi", "हिन्दी", "Hindi"),
        Language("th", "ไทย", "Thai"),
        Language("vi", "Tiếng Việt", "Vietnamese"),
        Language("id", "Bahasa Indonesia", "Indonesian"),
        Language("ms", "Bahasa Melayu", "Malay"),
        Language("tl", "Filipino", "Filipino"),
        Language("hu", "Magyar", "Hungarian"),
        Language("ro", "Română", "Romanian"),
        Language("bg", "Български", "Bulgarian"),
        Language("sr", "Српски", "Serbian"),
        Language("ka", "ქართული", "Georgian"),
        Language("hy", "Հայերեն", "Armenian"),
        Language("az", "Azərbaycan", "Azerbaijani"),
        Language("uz", "Oʻzbek", "Uzbek"),
    )

    private val byCode = all.associateBy { it.code }

    /** The language's own name, or the code for one not in the list. "Auto" is the UI's to say: see languageName. */
    fun nativeName(code: String): String = byCode[code]?.nativeName ?: code

    fun englishName(code: String): String = byCode[code]?.englishName ?: code
}
