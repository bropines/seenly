package io.github.bropines.lens

import android.content.Context
import android.content.Intent
import androidx.core.content.pm.ShortcutInfoCompat
import androidx.core.content.pm.ShortcutManagerCompat
import androidx.core.graphics.drawable.IconCompat
import io.github.bropines.lens.app.data.ViewMode

/**
 * Seenly's quick entries in the system share sheet: "Translate", "Text",
 * "Search", "Ask AI". Each is a sharing shortcut (res/xml/share_targets.xml);
 * a picture shared to one reaches [MainActivity] with that shortcut's id, and
 * the viewer opens in its mode. Which of them exist is a setting.
 */
internal object ShareShortcuts {
    private const val CATEGORY = "io.github.bropines.lens.category.SHARE_IMAGE"
    private const val PREFIX = "share_"

    /** The mode a share through one of these shortcuts asks for, or null for a plain share. */
    fun modeOf(shortcutId: String?): ViewMode? =
        shortcutId?.removePrefix(PREFIX)?.let { id -> ViewMode.entries.firstOrNull { it.name == id } }

    /** Puts exactly [modes] in the share sheet, in their usual order, labelled in the app's language. */
    fun publish(context: Context, modes: Set<ViewMode>) {
        val wanted = ViewMode.entries.filter { it in modes }
        val gone = ViewMode.entries.filter { it !in modes }.map { PREFIX + it.name }
        runCatching {
            if (gone.isNotEmpty()) ShortcutManagerCompat.removeLongLivedShortcuts(context, gone)
            ShortcutManagerCompat.removeDynamicShortcuts(context, gone)
            wanted.forEachIndexed { rank, mode ->
                val label = context.getString(labelOf(mode))
                val shortcut = ShortcutInfoCompat.Builder(context, PREFIX + mode.name)
                    .setShortLabel(label)
                    // The share sheet shows the long label when there is one.
                    .setLongLabel(label)
                    .setIcon(IconCompat.createWithResource(context, iconOf(mode)))
                    // From the launcher's menu the shortcut just opens the app.
                    .setIntent(Intent(Intent.ACTION_MAIN).setClass(context, MainActivity::class.java))
                    .setCategories(setOf(CATEGORY))
                    .setLongLived(true)
                    .setRank(rank)
                    .build()
                ShortcutManagerCompat.pushDynamicShortcut(context, shortcut)
            }
        }.onFailure { android.util.Log.w("ShareShortcuts", "share shortcuts not published", it) }
    }

    private fun labelOf(mode: ViewMode): Int = when (mode) {
        ViewMode.Translate -> R.string.share_translate
        ViewMode.Text -> R.string.share_text
        ViewMode.Search -> R.string.share_search
        ViewMode.AskAi -> R.string.share_ask_ai
    }

    private fun iconOf(mode: ViewMode): Int = when (mode) {
        ViewMode.Translate -> R.drawable.ic_share_translate
        ViewMode.Text -> R.drawable.ic_share_text
        ViewMode.Search -> R.drawable.ic_share_search
        ViewMode.AskAi -> R.drawable.ic_share_ask_ai
    }
}
