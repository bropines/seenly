package io.github.bropines.lens

import android.app.LocaleManager
import android.content.Context
import android.content.res.Configuration
import android.content.res.Resources
import android.os.Build
import android.os.LocaleList
import androidx.annotation.StringRes
import java.util.Locale

/**
 * The app's UI language (the `uiLanguage` setting), applied to both kinds of
 * strings: Android resources (toasts, the notification, labels) follow the
 * configuration, and Compose Multiplatform resources follow the default
 * locale (`Locale.getDefault()`, read by `stringResource` and `getString`).
 *
 * Android 13+: the system's per-app language does both. It persists the
 * choice, recreates the activities and lists the app in the system's language
 * settings (see res/xml/locales_config.xml), where it can be changed too.
 *
 * Older: the default locale is set here (and again after every configuration
 * change, which resets it), activities get a configuration context in the
 * chosen language, and the choice is mirrored in SharedPreferences so it is
 * known before anything else has started.
 */
object AppLocale {
    /** The languages the UI is translated into. */
    private val supported = setOf("en", "ru")
    private const val PREFS = "app_locale"
    private const val KEY = "language"

    /** "ru-RU" → "ru"; anything the UI is not translated into → null (the system's). */
    fun normalize(tag: String?): String? =
        tag?.takeIf { it.isNotEmpty() }?.let { Locale.forLanguageTag(it).language }?.takeIf { it in supported }

    /** The language in effect: the per-app language on 13+, our own record before. Null is the system's. */
    fun current(context: Context): String? =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            normalize(context.getSystemService(LocaleManager::class.java).applicationLocales.toLanguageTags().substringBefore(','))
        } else {
            prefs(context).getString(KEY, null)
        }

    /**
     * Switches the UI to [tag] (null: the system's). On 13+ the system
     * recreates what is on screen; before 13 this returns true when the
     * language changed and the caller has to recreate the activity.
     */
    fun apply(context: Context, tag: String?): Boolean {
        val wanted = normalize(tag)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (wanted != current(context)) {
                context.getSystemService(LocaleManager::class.java).applicationLocales =
                    if (wanted == null) LocaleList.getEmptyLocaleList() else LocaleList.forLanguageTags(wanted)
            }
            return false
        }
        val changed = wanted != prefs(context).getString(KEY, null)
        if (changed) {
            prefs(context).edit().apply { if (wanted == null) remove(KEY) else putString(KEY, wanted) }.apply()
        }
        applyDefault(context)
        return changed
    }

    /** Before 13: makes the default locale the chosen language again. Harmless to call often. */
    fun applyDefault(context: Context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) return
        val locales = prefs(context).getString(KEY, null)?.let { LocaleList.forLanguageTags(it) }
            ?: Resources.getSystem().configuration.locales
        if (LocaleList.getDefault() != locales) LocaleList.setDefault(locales)
    }

    /** Before 13: [base] with the chosen language in its configuration, for attachBaseContext. */
    fun wrap(base: Context): Context {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) return base
        applyDefault(base)
        val tag = prefs(base).getString(KEY, null) ?: return base
        val config = Configuration(base.resources.configuration).apply { setLocales(LocaleList.forLanguageTags(tag)) }
        return base.createConfigurationContext(config)
    }

    /** An Android string in the chosen language, from any context (a service, the application). */
    fun string(context: Context, @StringRes id: Int, vararg args: Any): String = wrap(context).resources.getString(id, *args)

    private fun prefs(context: Context) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
}
