package com.opencloudgaming.opennow

import android.annotation.SuppressLint
import android.graphics.Bitmap
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.AspectRatio
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Language
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.opencloudgaming.opennow.ui.theme.OpenNowPalette
import kotlin.math.roundToInt

private const val BROWSER_HOME_URL = "https://www.google.com"

/**
 * NanaPlay 1.0.26 — floating in-app browser window over the stream.
 *
 * Request member: open an interactive map (chest locations, character builds,
 * ...) without leaving the game. This is an in-app overlay inside the stream
 * activity — NOT a SYSTEM_ALERT_WINDOW, so no extra permission is needed.
 *
 * - Draggable via the header bar, closable, minimizable.
 * - Size presets S/M/L instead of free resize (predictable on phones).
 * - Simple address bar with back/forward navigation.
 * - Touches on the panel never reach the game (passthrough bounds registered,
 *   same mechanism as the other stream overlays).
 *
 * Light by design: the WebView is created lazily only while the panel is
 * open, destroyed on close (DisposableEffect), and paused (WebView.onPause)
 * while minimized so it doesn't burn CPU in the background.
 */
@SuppressLint("SetJavaScriptEnabled")
@Composable
fun NanaBrowserPanel(
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
) {
    BoxWithConstraints(modifier.fillMaxSize()) {
        val density = LocalDensity.current
        val maxWidthPx = with(density) { maxWidth.toPx() }
        val maxHeightPx = with(density) { maxHeight.toPx() }

        // Size presets: small / medium / large.
        var sizeIndex by remember { mutableIntStateOf(1) }
        val panelWidth: Dp
        val panelHeight: Dp
        when (sizeIndex) {
            0 -> { panelWidth = 300.dp; panelHeight = 420.dp }
            2 -> { panelWidth = maxWidth - 32.dp; panelHeight = maxHeight - 120.dp }
            else -> { panelWidth = 360.dp; panelHeight = 520.dp }
        }
        val panelWidthPx = with(density) { panelWidth.toPx() }.coerceAtMost(maxWidthPx)
        val panelHeightPx = with(density) { panelHeight.toPx() }.coerceAtMost(maxHeightPx)

        var offsetPx by remember(maxWidthPx, maxHeightPx) {
            mutableStateOf(
                Offset(
                    x = ((maxWidthPx - panelWidthPx) / 2f).coerceAtLeast(0f),
                    y = ((maxHeightPx - panelHeightPx) / 2f).coerceAtLeast(0f),
                ),
            )
        }
        var minimized by remember { mutableStateOf(false) }
        var addressText by remember { mutableStateOf(BROWSER_HOME_URL) }
        // 1.0.35: track whether the user is actively editing the address bar.
        // WebViewClient.onPageStarted fires on redirects/iframe loads and would
        // otherwise clobber the text right after the user taps X or types.
        var isAddressEditing by remember { mutableStateOf(false) }
        val keyboardController = LocalSoftwareKeyboardController.current
        var canGoBack by remember { mutableStateOf(false) }
        var canGoForward by remember { mutableStateOf(false) }
        var webViewRef by remember { mutableStateOf<WebView?>(null) }

        // Pause the page while minimized so it doesn't burn CPU; resume on expand.
        DisposableEffect(minimized) {
            val wv = webViewRef
            if (minimized) wv?.onPause() else wv?.onResume()
            onDispose { }
        }

        Surface(
            modifier = Modifier
                .offset { IntOffset(offsetPx.x.roundToInt(), offsetPx.y.roundToInt()) }
                // Register bounds so touches on the panel are NOT forwarded to the game.
                .streamTouchPassthrough(PASSTHROUGH_ID_BROWSER),
            shape = RoundedCornerShape(16.dp),
            color = OpenNowPalette.PanelOverVideo.copy(alpha = 0.97f),
            border = androidx.compose.foundation.BorderStroke(
                1.dp,
                OpenNowPalette.AccentDefault.copy(alpha = 0.4f),
            ),
            tonalElevation = 12.dp,
        ) {
            Column(
                Modifier
                    .width(with(density) { panelWidthPx.toDp() })
                    .height(if (minimized) 52.dp else with(density) { panelHeightPx.toDp() }),
            ) {
                // ---- header: drag handle + window controls ----
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(52.dp)
                        .background(OpenNowPalette.PanelAlt.copy(alpha = 0.9f))
                        .pointerInput(Unit) {
                            detectDragGestures(
                                onDrag = { change, dragAmount ->
                                    change.consume()
                                    offsetPx = Offset(
                                        x = (offsetPx.x + dragAmount.x)
                                            .coerceIn(0f, (maxWidthPx - panelWidthPx).coerceAtLeast(0f)),
                                        y = (offsetPx.y + dragAmount.y)
                                            .coerceIn(0f, (maxHeightPx - panelHeightPx).coerceAtLeast(0f)),
                                    )
                                },
                            )
                        }
                        .padding(horizontal = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(
                        imageVector = Icons.Filled.Language,
                        contentDescription = null,
                        tint = OpenNowPalette.AccentDefault,
                        modifier = Modifier
                            .padding(start = 8.dp)
                            .size(20.dp),
                    )
                    Text(
                        "Browser",
                        color = MaterialTheme.colorScheme.onSurface,
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Bold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier
                            .weight(1f)
                            .padding(start = 8.dp),
                    )
                    IconButton(
                        onClick = { sizeIndex = (sizeIndex + 1) % 3 },
                        modifier = Modifier.size(36.dp),
                    ) {
                        Icon(
                            imageVector = Icons.Filled.AspectRatio,
                            contentDescription = "Cycle size",
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(18.dp),
                        )
                    }
                    IconButton(
                        onClick = { minimized = !minimized },
                        modifier = Modifier.size(36.dp),
                    ) {
                        Icon(
                            imageVector = Icons.Filled.Remove,
                            contentDescription = if (minimized) "Expand" else "Minimize",
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(18.dp),
                        )
                    }
                    IconButton(
                        onClick = onClose,
                        modifier = Modifier.size(36.dp),
                    ) {
                        Icon(
                            imageVector = Icons.Filled.Close,
                            contentDescription = "Close browser",
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(18.dp),
                        )
                    }
                }

                if (!minimized) {
                    // ---- address bar ----
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 8.dp, vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(2.dp),
                    ) {
                        IconButton(
                            onClick = { webViewRef?.takeIf { canGoBack }?.goBack() },
                            enabled = canGoBack,
                            modifier = Modifier.size(36.dp),
                        ) {
                            Icon(
                                imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                                contentDescription = "Back",
                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.size(18.dp),
                            )
                        }
                        IconButton(
                            onClick = { webViewRef?.takeIf { canGoForward }?.goForward() },
                            enabled = canGoForward,
                            modifier = Modifier.size(36.dp),
                        ) {
                            Icon(
                                imageVector = Icons.AutoMirrored.Filled.ArrowForward,
                                contentDescription = "Forward",
                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.size(18.dp),
                            )
                        }
                        OutlinedTextField(
                            value = addressText,
                            onValueChange = { addressText = it },
                            modifier = Modifier
                                .weight(1f)
                                .height(48.dp)
                                // 1.0.35: track focus so onPageStarted doesn't
                                // clobber user input.
                                .onFocusChanged { isAddressEditing = it.isFocused },
                            singleLine = true,
                            textStyle = MaterialTheme.typography.bodySmall,
                            placeholder = { Text("Enter URL", style = MaterialTheme.typography.bodySmall) },
                            // 1.0.33: clear (X) button — was missing entirely.
                            trailingIcon = {
                                if (addressText.isNotEmpty()) {
                                    IconButton(onClick = { addressText = "" }) {
                                        Icon(
                                            imageVector = Icons.Filled.Close,
                                            contentDescription = "Clear",
                                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                            modifier = Modifier.size(18.dp),
                                        )
                                    }
                                }
                            },
                            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Go),
                            keyboardActions = KeyboardActions(
                                // 1.0.33: handle Go/Done/Search — some keyboards
                                // send Done or Search instead of Go.
                                // 1.0.35: stop editing + hide keyboard so the
                                // loaded URL can update the bar via onPageStarted.
                                onGo = {
                                    isAddressEditing = false
                                    keyboardController?.hide()
                                    webViewRef?.loadUrl(normalizeUrl(addressText))
                                },
                                onDone = {
                                    isAddressEditing = false
                                    keyboardController?.hide()
                                    webViewRef?.loadUrl(normalizeUrl(addressText))
                                },
                                onSearch = {
                                    isAddressEditing = false
                                    keyboardController?.hide()
                                    webViewRef?.loadUrl(normalizeUrl(addressText))
                                },
                            ),
                        )
                    }
                }

                // ---- web content (lazy: only composed while the panel is open) ----
                // Stays composed (but zero-height and paused) while minimized so the
                // page isn't lost; fully destroyed only when the panel closes.
                Box(
                    Modifier
                        .fillMaxWidth()
                        .then(if (minimized) Modifier.height(0.dp) else Modifier.weight(1f))
                        .padding(horizontal = 8.dp)
                        .padding(bottom = if (minimized) 0.dp else 8.dp)
                        .background(Color.White, RoundedCornerShape(8.dp)),
                ) {
                    AndroidView(
                        factory = { ctx ->
                            WebView(ctx).apply {
                                settings.javaScriptEnabled = true
                                settings.domStorageEnabled = true
                                settings.cacheMode = WebSettings.LOAD_DEFAULT
                                settings.mediaPlaybackRequiresUserGesture = false
                                webViewClient = object : WebViewClient() {
                                    override fun shouldOverrideUrlLoading(
                                        view: WebView,
                                        request: WebResourceRequest,
                                    ): Boolean = false // stay inside the panel

                                    override fun onPageStarted(view: WebView, url: String, favicon: Bitmap?) {
                                        // 1.0.35: don't overwrite what the user is
                                        // typing (or just cleared with X).
                                        if (!isAddressEditing) {
                                            addressText = url
                                        }
                                    }

                                    override fun onPageFinished(view: WebView, url: String) {
                                        canGoBack = view.canGoBack()
                                        canGoForward = view.canGoForward()
                                    }
                                }
                                webChromeClient = WebChromeClient()
                                webViewRef = this
                                loadUrl(BROWSER_HOME_URL)
                            }
                        },
                        modifier = Modifier.fillMaxSize(),
                    )
                    DisposableEffect(Unit) {
                        onDispose {
                            // Free everything when the panel closes.
                            webViewRef?.apply {
                                stopLoading()
                                onPause()
                                removeAllViews()
                                destroy()
                            }
                            webViewRef = null
                        }
                    }
                }
            }
        }

        // Keep the panel inside the screen when its size preset changes.
        DisposableEffect(panelWidthPx, panelHeightPx) {
            offsetPx = Offset(
                x = offsetPx.x.coerceIn(0f, (maxWidthPx - panelWidthPx).coerceAtLeast(0f)),
                y = offsetPx.y.coerceIn(0f, (maxHeightPx - panelHeightPx).coerceAtLeast(0f)),
            )
            onDispose { }
        }
    }
}

private fun normalizeUrl(input: String): String {
    val trimmed = input.trim()
    if (trimmed.isBlank()) return BROWSER_HOME_URL
    return when {
        trimmed.startsWith("http://", ignoreCase = true) ||
            trimmed.startsWith("https://", ignoreCase = true) -> trimmed
        // Treat bare words as a search.
        !trimmed.contains('.') && !trimmed.contains('/') -> {
            "https://www.google.com/search?q=" +
                java.net.URLEncoder.encode(trimmed, "UTF-8")
        }
        else -> "https://$trimmed"
    }
}

// NanaPlay 1.0.26: floating in-stream browser (visible to OpenNowScreens).
internal const val PASSTHROUGH_ID_BROWSER = "browser-panel"
