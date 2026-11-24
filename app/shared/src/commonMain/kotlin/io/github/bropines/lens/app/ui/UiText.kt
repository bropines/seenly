package io.github.bropines.lens.app.ui

import androidx.compose.runtime.Composable
import org.jetbrains.compose.resources.StringResource
import org.jetbrains.compose.resources.stringResource

/**
 * Text a ViewModel hands the UI: a string resource the UI resolves in the
 * current language, or text that arrived ready-made (a server's message).
 * ViewModels outlive a language switch, so they keep the resource, not a string.
 */
sealed interface UiText {
    data class Resource(val res: StringResource, val args: List<Any> = emptyList()) : UiText
    data class Plain(val text: String) : UiText
}

fun uiText(res: StringResource, vararg args: Any): UiText = UiText.Resource(res, args.toList())

@Composable
fun UiText.asString(): String = when (this) {
    is UiText.Resource -> stringResource(res, *args.toTypedArray())
    is UiText.Plain -> text
}
