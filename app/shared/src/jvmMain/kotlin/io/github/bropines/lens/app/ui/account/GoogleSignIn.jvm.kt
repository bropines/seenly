package io.github.bropines.lens.app.ui.account

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect

/** The desktop has no embedded browser: an account is connected by importing cookies. */
actual val googleSignInSupported: Boolean = false

@Composable
actual fun GoogleSignInScreen(onCookies: (String) -> Boolean, onEmail: (String?) -> Unit, onBack: () -> Unit) {
    // Never reached: the entry that opens it is hidden without googleSignInSupported.
    LaunchedEffect(Unit) { onBack() }
}
