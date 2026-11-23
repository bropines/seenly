package io.github.bropines.lens.screen

import android.annotation.SuppressLint
import android.content.Context
import android.os.Build
import android.view.KeyEvent
import android.view.WindowManager
import android.widget.FrameLayout
import android.window.OnBackInvokedCallback
import android.window.OnBackInvokedDispatcher
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.setViewTreeLifecycleOwner
import androidx.savedstate.SavedStateRegistry
import androidx.savedstate.SavedStateRegistryController
import androidx.savedstate.SavedStateRegistryOwner
import androidx.savedstate.setViewTreeSavedStateRegistryOwner
import io.github.bropines.lens.app.data.AppSettings
import io.github.bropines.lens.app.data.Languages
import io.github.bropines.lens.app.data.SettingsRepository
import io.github.bropines.lens.app.ui.theme.LensTheme

/**
 * The bubble's menu: mode, languages, stop. A sheet at the bottom of the
 * screen, where the thumb is, in a window of its own over whatever app is open.
 */
internal class BubbleMenu(
    private val context: Context,
    private val wm: WindowManager,
    private val settings: SettingsRepository,
    private val listener: Listener,
) {
    interface Listener {
        fun onMode(mode: ScreenMode)
        /** The main button: translate now ("this screen") or just carry on. */
        fun onPrimary()
        fun onLanguage(source: Boolean, code: String?)
        fun onStop()
        fun onMenuClosed()
    }

    private var root: Root? = null
    private var owner: Owner? = null
    private var mode by mutableStateOf(ScreenMode.Screen)

    val isShown: Boolean get() = root != null

    fun show(current: ScreenMode, bottomInset: Int) {
        if (root != null) return
        mode = current
        val density = context.resources.displayMetrics.density
        val owner = Owner().also { this.owner = it }
        val view = Root(context).apply {
            setViewTreeLifecycleOwner(owner)
            setViewTreeSavedStateRegistryOwner(owner)
            addView(
                ComposeView(context).apply {
                    setContent {
                        MenuSheet(
                            settings = settings,
                            mode = mode,
                            bottomInset = (bottomInset / density).dp,
                            onMode = {
                                mode = it
                                listener.onMode(it)
                            },
                            onPrimary = {
                                hide()
                                listener.onPrimary()
                            },
                            onLanguage = listener::onLanguage,
                            onStop = {
                                hide()
                                listener.onStop()
                            },
                            onDismiss = ::hide,
                        )
                    }
                },
            )
        }
        val params = overlayParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.FLAG_DIM_BEHIND,
        ).apply { dimAmount = 0.4f }
        owner.resume()
        wm.addView(view, params)
        root = view
    }

    fun hide() {
        val view = root ?: return
        root = null
        runCatching { wm.removeViewImmediate(view) }
        owner?.destroy()
        owner = null
        listener.onMenuClosed()
    }

    /** Takes "back" for the menu: the key on old systems, the back callback on new ones. */
    @SuppressLint("ViewConstructor")
    private inner class Root(context: Context) : FrameLayout(context) {
        private var back: Any? = null

        override fun dispatchKeyEvent(event: KeyEvent): Boolean {
            if (event.keyCode == KeyEvent.KEYCODE_BACK) {
                if (event.action == KeyEvent.ACTION_UP) hide()
                return true
            }
            return super.dispatchKeyEvent(event)
        }

        override fun onAttachedToWindow() {
            super.onAttachedToWindow()
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                val callback = OnBackInvokedCallback { hide() }
                findOnBackInvokedDispatcher()?.registerOnBackInvokedCallback(OnBackInvokedDispatcher.PRIORITY_DEFAULT, callback)
                back = callback
            }
        }

        override fun onDetachedFromWindow() {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                (back as? OnBackInvokedCallback)?.let { findOnBackInvokedDispatcher()?.unregisterOnBackInvokedCallback(it) }
                back = null
            }
            super.onDetachedFromWindow()
        }
    }

    /** A service has no lifecycle for Compose to hang on; the menu brings its own. */
    private class Owner : LifecycleOwner, SavedStateRegistryOwner {
        private val registry = LifecycleRegistry(this)
        private val saved = SavedStateRegistryController.create(this)
        override val lifecycle: Lifecycle get() = registry
        override val savedStateRegistry: SavedStateRegistry get() = saved.savedStateRegistry

        fun resume() {
            saved.performRestore(null)
            registry.currentState = Lifecycle.State.RESUMED
        }

        fun destroy() {
            registry.currentState = Lifecycle.State.DESTROYED
        }
    }
}

private enum class Picking { Source, Target }

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun MenuSheet(
    settings: SettingsRepository,
    mode: ScreenMode,
    bottomInset: Dp,
    onMode: (ScreenMode) -> Unit,
    onPrimary: () -> Unit,
    onLanguage: (source: Boolean, code: String?) -> Unit,
    onStop: () -> Unit,
    onDismiss: () -> Unit,
) {
    val current by settings.settings.collectAsState(AppSettings())
    var picking by remember { mutableStateOf<Picking?>(null) }
    LensTheme(current.theme) {
        Box(
            Modifier.fillMaxSize().clickable(remember { MutableInteractionSource() }, indication = null, onClick = onDismiss),
            contentAlignment = Alignment.BottomCenter,
        ) {
            Surface(
                shape = RoundedCornerShape(28.dp),
                color = MaterialTheme.colorScheme.surfaceContainerHigh,
                modifier = Modifier
                    .padding(start = 12.dp, end = 12.dp, bottom = bottomInset + 12.dp)
                    .widthIn(max = 480.dp)
                    .fillMaxWidth()
                    // A tap on the sheet itself is not a tap outside it.
                    .clickable(remember { MutableInteractionSource() }, indication = null) {},
            ) {
                when (val pick = picking) {
                    null -> Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                        Text("Перевод экрана", style = MaterialTheme.typography.titleLarge)
                        SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                            val modes = ScreenMode.entries
                            modes.forEachIndexed { index, item ->
                                SegmentedButton(
                                    selected = item == mode,
                                    onClick = { onMode(item) },
                                    shape = SegmentedButtonDefaults.itemShape(index, modes.size),
                                    icon = {},
                                ) {
                                    Text(
                                        when (item) {
                                            ScreenMode.Screen -> "Экран"
                                            ScreenMode.Live -> "Авто"
                                            ScreenMode.Area -> "Область"
                                        },
                                    )
                                }
                            }
                        }
                        Text(
                            when (mode) {
                                ScreenMode.Screen -> "Тап по пузырю переводит то, что сейчас на экране. Тап в любом месте убирает перевод."
                                ScreenMode.Live -> "Переводит сам, как только картинка остановилась; пока листаете, перевод скрыт. Тап по пузырю — пауза."
                                ScreenMode.Area -> "Переводит только внутри рамки, когда её содержимое меняется. Рамку можно двигать и растягивать."
                            },
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            FilledTonalButton(onClick = { picking = Picking.Source }, modifier = Modifier.weight(1f)) {
                                Text(Languages.nameOf(current.sourceLanguage), maxLines = 1, overflow = TextOverflow.Ellipsis)
                            }
                            Text("→", style = MaterialTheme.typography.titleMedium)
                            FilledTonalButton(onClick = { picking = Picking.Target }, modifier = Modifier.weight(1f)) {
                                Text(Languages.nameOf(current.targetLanguage), maxLines = 1, overflow = TextOverflow.Ellipsis)
                            }
                        }
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            OutlinedButton(onClick = onStop, modifier = Modifier.weight(1f)) { Text("Выключить") }
                            Button(onClick = onPrimary, modifier = Modifier.weight(1f)) {
                                Text(if (mode == ScreenMode.Screen) "Перевести" else "Готово")
                            }
                        }
                        Text(
                            "Удержание пузыря открывает это меню",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    else -> Column(Modifier.padding(vertical = 12.dp)) {
                        Row(Modifier.padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                            TextButton(onClick = { picking = null }) { Text("‹ Назад") }
                            Text(
                                if (pick == Picking.Source) "Язык оригинала" else "Язык перевода",
                                style = MaterialTheme.typography.titleMedium,
                            )
                        }
                        val selected = if (pick == Picking.Source) current.sourceLanguage else current.targetLanguage
                        // The current choice first, so it is confirmed without scrolling.
                        val codes: List<String?> = buildList {
                            if (pick == Picking.Source) add(null)
                            addAll(Languages.all.map { it.code })
                        }.sortedBy { if (it == selected) 0 else 1 }
                        LazyColumn(Modifier.heightIn(max = 400.dp)) {
                            items(codes) { code ->
                                val chosen = code == selected
                                Row(
                                    Modifier
                                        .fillMaxWidth()
                                        .clickable {
                                            onLanguage(pick == Picking.Source, code)
                                            picking = null
                                        }
                                        .padding(horizontal = 24.dp, vertical = 14.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    Text(
                                        Languages.nameOf(code),
                                        style = MaterialTheme.typography.bodyLarge,
                                        fontWeight = if (chosen) FontWeight.SemiBold else FontWeight.Normal,
                                        color = if (chosen) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
                                        modifier = Modifier.weight(1f),
                                    )
                                    if (chosen) Text("✓", color = MaterialTheme.colorScheme.primary)
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}
