package io.github.bropines.lens.app.ui.account

import android.annotation.SuppressLint
import android.graphics.Bitmap
import android.webkit.CookieManager
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import io.github.bropines.lens.app.resources.*
import org.jetbrains.compose.resources.stringResource

actual val googleSignInSupported: Boolean = true

/** The site whose cookies are the session, and the sign-in page that leads back to it. */
private const val SIGNED_IN = "https://www.google.com/"
private const val SIGN_IN = "https://accounts.google.com/ServiceLogin?continue=https%3A%2F%2Fwww.google.com%2F"

/**
 * Google's pages for a browser it will not sign in ("This browser or app may
 * not be secure"). An embedded WebView often gets one; it is not worked
 * around by pretending to be another browser.
 */
private fun isRefusal(url: String): Boolean = "/signin/rejected" in url || "disallowed_useragent" in url

@SuppressLint("SetJavaScriptEnabled") // Google's sign-in page does not work without scripts.
@Composable
actual fun GoogleSignInScreen(onCookies: (String) -> Boolean, onBack: () -> Unit) {
    val context = LocalContext.current
    val deliver by rememberUpdatedState(onCookies)
    var loading by remember { mutableStateOf(true) }
    var refused by remember { mutableStateOf(false) }
    var done by remember { mutableStateOf(false) }

    val web = remember {
        WebView(context).apply {
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            webViewClient = object : WebViewClient() {
                override fun onPageStarted(view: WebView, url: String, favicon: Bitmap?) {
                    loading = true
                    if (isRefusal(url)) refused = true
                }

                override fun onPageFinished(view: WebView, url: String) {
                    loading = false
                    if (isRefusal(url)) refused = true
                    if (done) return
                    // Asked after every page rather than at one final address, which Google is free to
                    // change: google.com has session cookies only once the user has signed in.
                    val cookies = CookieManager.getInstance().getCookie(SIGNED_IN).orEmpty()
                    if (deliver(cookies)) {
                        done = true
                        forgetWebCookies()
                    }
                }
            }
        }
    }
    DisposableEffect(web) {
        // Start clean: a session left by an earlier, abandoned attempt must not be picked up as this one.
        CookieManager.getInstance().removeAllCookies { web.loadUrl(SIGN_IN) }
        onDispose {
            web.stopLoading()
            web.destroy()
            forgetWebCookies()
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(Res.string.account_sign_in)) },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, stringResource(Res.string.back)) } },
            )
        },
    ) { padding ->
        if (refused) {
            Column(Modifier.fillMaxSize().padding(padding).padding(24.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                Text(stringResource(Res.string.account_sign_in_refused_title), style = MaterialTheme.typography.titleMedium)
                Text(stringResource(Res.string.account_sign_in_refused))
                Button(onClick = onBack, modifier = Modifier.fillMaxWidth()) { Text(stringResource(Res.string.account_sign_in_refused_action)) }
            }
        } else {
            Box(Modifier.fillMaxSize().padding(padding)) {
                AndroidView(factory = { web }, modifier = Modifier.fillMaxSize())
                if (loading) LinearProgressIndicator(Modifier.fillMaxWidth())
            }
        }
    }
}

/**
 * The WebView keeps its own cookie jar, unencrypted. Once the session is in
 * the app's store (or the attempt is over) nothing should stay there; the
 * session itself is not signed out by this.
 */
private fun forgetWebCookies() {
    CookieManager.getInstance().run {
        removeAllCookies(null)
        flush()
    }
}
