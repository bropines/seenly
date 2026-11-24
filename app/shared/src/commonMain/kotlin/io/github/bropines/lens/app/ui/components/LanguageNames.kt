package io.github.bropines.lens.app.ui.components

import androidx.compose.runtime.Composable
import io.github.bropines.lens.app.data.Languages
import io.github.bropines.lens.app.resources.Res
import io.github.bropines.lens.app.resources.auto
import io.github.bropines.lens.app.resources.lang_ar
import io.github.bropines.lens.app.resources.lang_az
import io.github.bropines.lens.app.resources.lang_be
import io.github.bropines.lens.app.resources.lang_bg
import io.github.bropines.lens.app.resources.lang_cs
import io.github.bropines.lens.app.resources.lang_da
import io.github.bropines.lens.app.resources.lang_de
import io.github.bropines.lens.app.resources.lang_el
import io.github.bropines.lens.app.resources.lang_en
import io.github.bropines.lens.app.resources.lang_es
import io.github.bropines.lens.app.resources.lang_fa
import io.github.bropines.lens.app.resources.lang_fi
import io.github.bropines.lens.app.resources.lang_fr
import io.github.bropines.lens.app.resources.lang_hi
import io.github.bropines.lens.app.resources.lang_hu
import io.github.bropines.lens.app.resources.lang_hy
import io.github.bropines.lens.app.resources.lang_id
import io.github.bropines.lens.app.resources.lang_it
import io.github.bropines.lens.app.resources.lang_iw
import io.github.bropines.lens.app.resources.lang_ja
import io.github.bropines.lens.app.resources.lang_ka
import io.github.bropines.lens.app.resources.lang_kk
import io.github.bropines.lens.app.resources.lang_ko
import io.github.bropines.lens.app.resources.lang_ms
import io.github.bropines.lens.app.resources.lang_nl
import io.github.bropines.lens.app.resources.lang_no
import io.github.bropines.lens.app.resources.lang_pl
import io.github.bropines.lens.app.resources.lang_pt
import io.github.bropines.lens.app.resources.lang_ro
import io.github.bropines.lens.app.resources.lang_ru
import io.github.bropines.lens.app.resources.lang_sr
import io.github.bropines.lens.app.resources.lang_sv
import io.github.bropines.lens.app.resources.lang_th
import io.github.bropines.lens.app.resources.lang_tl
import io.github.bropines.lens.app.resources.lang_tr
import io.github.bropines.lens.app.resources.lang_uk
import io.github.bropines.lens.app.resources.lang_uz
import io.github.bropines.lens.app.resources.lang_vi
import io.github.bropines.lens.app.resources.lang_zh_cn
import io.github.bropines.lens.app.resources.lang_zh_tw
import org.jetbrains.compose.resources.StringResource
import org.jetbrains.compose.resources.stringResource

/** Each language's name in the UI language, by its code in [Languages]. */
private val localizedNames: Map<String, StringResource> = mapOf(
    "ru" to Res.string.lang_ru,
    "en" to Res.string.lang_en,
    "ja" to Res.string.lang_ja,
    "ko" to Res.string.lang_ko,
    "zh-CN" to Res.string.lang_zh_cn,
    "zh-TW" to Res.string.lang_zh_tw,
    "uk" to Res.string.lang_uk,
    "be" to Res.string.lang_be,
    "kk" to Res.string.lang_kk,
    "de" to Res.string.lang_de,
    "fr" to Res.string.lang_fr,
    "es" to Res.string.lang_es,
    "it" to Res.string.lang_it,
    "pt" to Res.string.lang_pt,
    "pl" to Res.string.lang_pl,
    "cs" to Res.string.lang_cs,
    "nl" to Res.string.lang_nl,
    "sv" to Res.string.lang_sv,
    "fi" to Res.string.lang_fi,
    "no" to Res.string.lang_no,
    "da" to Res.string.lang_da,
    "tr" to Res.string.lang_tr,
    "el" to Res.string.lang_el,
    "iw" to Res.string.lang_iw,
    "ar" to Res.string.lang_ar,
    "fa" to Res.string.lang_fa,
    "hi" to Res.string.lang_hi,
    "th" to Res.string.lang_th,
    "vi" to Res.string.lang_vi,
    "id" to Res.string.lang_id,
    "ms" to Res.string.lang_ms,
    "tl" to Res.string.lang_tl,
    "hu" to Res.string.lang_hu,
    "ro" to Res.string.lang_ro,
    "bg" to Res.string.lang_bg,
    "sr" to Res.string.lang_sr,
    "ka" to Res.string.lang_ka,
    "hy" to Res.string.lang_hy,
    "az" to Res.string.lang_az,
    "uz" to Res.string.lang_uz,
)

/** A language as the app labels it: "Auto" (in the UI language) for none, else its own native name. */
@Composable
fun languageName(code: String?): String =
    if (code.isNullOrEmpty()) stringResource(Res.string.auto) else Languages.nativeName(code)

/** A language's name in the UI language ("Japanese", "Японский"), for subtitles and search. */
@Composable
fun localizedLanguageName(code: String): String =
    localizedNames[code]?.let { stringResource(it) } ?: Languages.englishName(code)
