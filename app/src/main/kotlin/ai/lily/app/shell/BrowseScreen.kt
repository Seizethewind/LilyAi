package ai.lily.app.shell

import android.content.Context
import android.net.Uri
import android.view.ViewGroup
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView

/** The browser's page, kept alive while the person visits another section. [context] must be the activity. */
class BrowseState(context: Context) {
    var address by mutableStateOf("")

    val web: WebView = WebView(context).apply {
        settings.javaScriptEnabled = true
        settings.allowFileAccess = false
        settings.allowContentAccess = false
        webViewClient = object : WebViewClient() {
            override fun onPageFinished(view: WebView?, url: String?) {
                if (url != null) address = url
            }
        }
    }

    /** An address is opened; anything else is searched. */
    fun go(input: String) {
        val text = input.trim()
        if (text.isEmpty()) return
        val url = when {
            text.startsWith("http://") || text.startsWith("https://") -> text
            ' ' !in text && '.' in text -> "https://$text"
            else -> "https://duckduckgo.com/?q=" + Uri.encode(text)
        }
        web.loadUrl(url)
    }
}

/** The Browse section: a web page with a search or address bar. */
@Composable
fun BrowseScreen(state: BrowseState, modifier: Modifier = Modifier) {
    var text by remember { mutableStateOf(state.address) }
    LaunchedEffect(state.address) { text = state.address }
    BackHandler(enabled = state.address.isNotEmpty() && state.web.canGoBack()) { state.web.goBack() }

    Column(modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding().imePadding()) {
        Row(Modifier.fillMaxWidth().padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(
                value = text,
                onValueChange = { text = it },
                singleLine = true,
                placeholder = { Text("Search or enter an address") },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, imeAction = ImeAction.Go),
                keyboardActions = KeyboardActions(onGo = { state.go(text) }),
                modifier = Modifier.weight(1f),
            )
            TextButton(onClick = { state.web.reload() }) { Text("Reload") }
        }
        AndroidView(
            factory = {
                // The page may still hang under the previous screen's holder after a visit elsewhere.
                (state.web.parent as? ViewGroup)?.removeView(state.web)
                state.web
            },
            modifier = Modifier.fillMaxWidth().weight(1f),
        )
    }
}
