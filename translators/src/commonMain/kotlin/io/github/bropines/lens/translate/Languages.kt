package io.github.bropines.lens.translate

/**
 * The internal language table. Tags are BCP 47 with these choices where
 * engines disagree: `he` (not iw), `fil` (not tl), `nb` (not no), `jv` (not
 * jw), `zh-Hans`/`zh-Hant` (not zh-CN/zh-TW), `sr` and `mn` for the Cyrillic
 * scripts, `hmn` for Hmong.
 */
public object Languages {
    /** Tag to English name, in alphabetical order of the name. */
    public val names: Map<String, String> = (
        "af=Afrikaans;sq=Albanian;am=Amharic;ar=Arabic;hy=Armenian;az=Azerbaijani;eu=Basque;be=Belarusian;" +
            "bn=Bengali;bs=Bosnian;bg=Bulgarian;my=Burmese;ca=Catalan;ceb=Cebuano;ny=Chichewa;" +
            "zh-Hans=Chinese (Simplified);zh-Hant=Chinese (Traditional);co=Corsican;hr=Croatian;cs=Czech;" +
            "da=Danish;nl=Dutch;en=English;eo=Esperanto;et=Estonian;fil=Filipino;fi=Finnish;fr=French;" +
            "fy=Frisian;gl=Galician;ka=Georgian;de=German;el=Greek;gu=Gujarati;ht=Haitian Creole;ha=Hausa;" +
            "haw=Hawaiian;he=Hebrew;hi=Hindi;hmn=Hmong;hu=Hungarian;is=Icelandic;ig=Igbo;id=Indonesian;" +
            "ga=Irish;it=Italian;ja=Japanese;jv=Javanese;kn=Kannada;kk=Kazakh;km=Khmer;rw=Kinyarwanda;" +
            "ko=Korean;ku=Kurdish;ky=Kyrgyz;lo=Lao;la=Latin;lv=Latvian;lt=Lithuanian;lb=Luxembourgish;" +
            "mk=Macedonian;mg=Malagasy;ms=Malay;ml=Malayalam;mt=Maltese;mi=Maori;mr=Marathi;mn=Mongolian;" +
            "ne=Nepali;nb=Norwegian;or=Odia;ps=Pashto;fa=Persian;pl=Polish;pt=Portuguese;pa=Punjabi;" +
            "ro=Romanian;ru=Russian;sm=Samoan;gd=Scottish Gaelic;sr=Serbian;st=Sesotho;sn=Shona;sd=Sindhi;" +
            "si=Sinhala;sk=Slovak;sl=Slovenian;so=Somali;es=Spanish;su=Sundanese;sw=Swahili;sv=Swedish;" +
            "tg=Tajik;ta=Tamil;tt=Tatar;te=Telugu;th=Thai;tr=Turkish;tk=Turkmen;uk=Ukrainian;ur=Urdu;" +
            "ug=Uyghur;uz=Uzbek;vi=Vietnamese;cy=Welsh;xh=Xhosa;yi=Yiddish;yo=Yoruba;zu=Zulu"
        ).split(';').associate { it.substringBefore('=') to it.substringAfter('=') }

    /** Regional variants some engines tell apart; elsewhere they fall back to the base language. */
    public val variants: Map<String, String> = mapOf(
        "en-US" to "English (US)",
        "en-GB" to "English (UK)",
        "pt-BR" to "Portuguese (Brazil)",
        "pt-PT" to "Portuguese (Portugal)",
    )

    /** Every tag of the table, base languages only. */
    public val all: Set<String> get() = names.keys

    private val aliases = mapOf("iw" to "he", "tl" to "fil", "no" to "nb", "jw" to "jv", "in" to "id", "ji" to "yi", "mww" to "hmn")
    private val traditional = setOf("hant", "tw", "hk", "mo")

    /**
     * Turns any spelling a user, a system locale or an engine might produce
     * (`zh_CN`, `iw`, `PT-br`, `zh-TW`) into the internal tag.
     */
    public fun normalize(tag: String): String {
        val parts = tag.trim().replace('_', '-').split('-').filter { it.isNotEmpty() }
        if (parts.isEmpty()) return ""
        val language = parts[0].lowercase().let { aliases[it] ?: it }
        val rest = parts.drop(1)
        if (language == "zh") return if (rest.any { it.lowercase() in traditional }) "zh-Hant" else "zh-Hans"
        return (listOf(language) + rest.map {
            when (it.length) {
                4 -> it.lowercase().replaceFirstChar(Char::uppercaseChar)
                2 -> it.uppercase()
                else -> it
            }
        }).joinToString("-")
    }

    /** `pt-BR` gives `pt`. */
    public fun base(tag: String): String = tag.substringBefore('-')

    /** English name of [tag], or the tag itself when the table does not know it. */
    public fun name(tag: String): String {
        val normal = normalize(tag)
        return names[normal] ?: variants[normal] ?: names[base(normal)] ?: tag
    }
}

/**
 * What one engine calls the languages of the internal table.
 *
 * @param supported internal tags the engine knows, or null when the set is
 *   open (a self-hosted server, an LLM): then any tag is passed through.
 * @param codes internal tag to the engine's code, where they differ.
 * @param sourceCodes overrides of [codes] that apply to the source only.
 * @param targetCodes overrides of [codes] that apply to the target only
 *   (DeepL wants `en-US` as a target but `en` as a source).
 * @param responseCodes extra engine codes that only appear in responses,
 *   engine code to internal tag.
 * @param upperCase the engine wants its codes in upper case.
 */
public class LanguageMap(
    supported: Collection<String>? = null,
    private val codes: Map<String, String> = emptyMap(),
    private val sourceCodes: Map<String, String> = emptyMap(),
    private val targetCodes: Map<String, String> = emptyMap(),
    responseCodes: Map<String, String> = emptyMap(),
    private val upperCase: Boolean = false,
) {
    private val known: Set<String>? = supported?.let { it.toSet() + codes.keys + sourceCodes.keys + targetCodes.keys }
    private val reverse: Map<String, String> = buildMap {
        // A detected language is a source, so target-only codes stay out. First writer wins,
        // so a code shared by two tags (Yandex's zh) maps to the first. A code that is only
        // the tag's base language (DeepL's en for en-GB) must not turn every "en" into en-GB.
        (sourceCodes.entries + codes.entries).forEach { (tag, code) ->
            if (code.lowercase() !in this && Languages.normalize(code) != Languages.base(tag)) put(code.lowercase(), tag)
        }
        responseCodes.forEach { (code, tag) -> put(code.lowercase(), tag) }
    }

    /** Internal tags the engine knows, or null when it takes anything. */
    public val supported: Set<String>? get() = known

    /**
     * The engine's code for [tag], or null when the engine does not know the
     * language. A regional variant the engine lacks falls back to its base
     * language.
     */
    public fun toProvider(tag: String, target: Boolean): String? {
        val normal = Languages.normalize(tag)
        if (normal.isEmpty()) return null
        val special = if (target) targetCodes else sourceCodes
        for (candidate in listOf(normal, Languages.base(normal)).distinct()) {
            val code = special[candidate] ?: codes[candidate] ?: candidate.takeIf { known == null || it in known }
            if (code != null) return if (upperCase) code.uppercase() else code
        }
        return null
    }

    /** The internal tag for a code the engine returned. */
    public fun fromProvider(code: String): String = reverse[code.lowercase()] ?: Languages.normalize(code)

    public fun supportsTarget(tag: String): Boolean = toProvider(tag, target = true) != null
    public fun supportsSource(tag: String): Boolean = toProvider(tag, target = false) != null
}
