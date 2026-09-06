package com.snaptube.downloader.ui.screens

import android.annotation.SuppressLint
import android.graphics.Bitmap
import android.os.Handler
import android.os.Looper
import android.webkit.JavascriptInterface
import android.webkit.WebChromeClient
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.ArrowForward
import androidx.compose.material.icons.filled.FileDownload
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import com.snaptube.downloader.core.download.DownloadHelper
import com.snaptube.downloader.core.extractor.VideoExtractorEngine
import com.snaptube.downloader.core.model.ResolveResult
import com.snaptube.downloader.data.model.MediaInfo
import com.snaptube.downloader.ui.components.DownloadBottomSheet
import com.snaptube.downloader.ui.theme.SnaptubeBlack
import com.snaptube.downloader.ui.theme.SnaptubeCard
import com.snaptube.downloader.ui.theme.SnaptubeTextPrimary
import com.snaptube.downloader.ui.theme.SnaptubeTextSecondary
import com.snaptube.downloader.ui.theme.SnaptubeYellow
import kotlinx.coroutines.launch

private class VidSnapMediaBridge(
    private val onMediaDetected: (url: String, title: String?, poster: String?) -> Unit
) {
    @JavascriptInterface
    fun onMediaFound(url: String?, title: String?, poster: String?) {
        if (!url.isNullOrBlank() && url.startsWith("http")) {
            Handler(Looper.getMainLooper()).post {
                onMediaDetected(url, title, poster)
            }
        }
    }
}

private const val SNIFFER_JS = """
(function() {
    if (window.__vidsnap_hooked) return;
    window.__vidsnap_hooked = true;

    function reportMedia(url, poster) {
        if (!url || typeof url !== 'string') return;
        if (url.indexOf('blob:') === 0 || url.indexOf('http') !== 0) return;
        var t = document.title || '';
        try {
            if (window.VidSnapBridge) {
                window.VidSnapBridge.onMediaFound(url, t, poster || '');
            }
        } catch(e) {}
    }

    // 1. Hook HTMLMediaElement
    var origPlay = HTMLMediaElement.prototype.play;
    HTMLMediaElement.prototype.play = function() {
        var src = this.currentSrc || this.src;
        if (src) reportMedia(src, this.poster);
        return origPlay.apply(this, arguments);
    };

    // 2. Hook XHR for streaming media endpoints
    var origOpen = XMLHttpRequest.prototype.open;
    XMLHttpRequest.prototype.open = function(method, url) {
        if (typeof url === 'string') {
            var u = url.toLowerCase();
            if ((u.indexOf('.mp4') !== -1 || u.indexOf('videoplayback') !== -1 || u.indexOf('mime=video') !== -1) &&
                u.indexOf('.jpg') === -1 && u.indexOf('.png') === -1 && u.indexOf('.webp') === -1) {
                reportMedia(url, '');
            }
        }
        return origOpen.apply(this, arguments);
    };

    // 3. Hook Fetch API
    if (window.fetch) {
        var origFetch = window.fetch;
        window.fetch = function(input, init) {
            var u = typeof input === 'string' ? input : (input && input.url ? input.url : '');
            if (u && typeof u === 'string') {
                var ul = u.toLowerCase();
                if ((ul.indexOf('.mp4') !== -1 || ul.indexOf('videoplayback') !== -1 || ul.indexOf('mime=video') !== -1) &&
                    ul.indexOf('.jpg') === -1 && ul.indexOf('.png') === -1) {
                    reportMedia(u, '');
                }
            }
            return origFetch.apply(this, arguments);
        };
    }

    // 4. Periodic DOM scan for video elements
    setInterval(function() {
        var vids = document.getElementsByTagName('video');
        for (var i = 0; i < vids.length; i++) {
            var s = vids[i].currentSrc || vids[i].src;
            if (s && s.indexOf('http') === 0 && s.indexOf('blob:') !== 0) {
                reportMedia(s, vids[i].poster);
            }
        }
    }, 1500);
})();
"""

@SuppressLint("SetJavaScriptEnabled")
@Composable
fun BrowserScreen(
    initialUrl: String,
    onBack: () -> Unit = {},
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()

    var webViewInstance by remember { mutableStateOf<WebView?>(null) }
    var currentUrl by remember { mutableStateOf(initialUrl) }
    var pageTitle by remember { mutableStateOf("Loading...") }
    var loadProgress by remember { mutableFloatStateOf(0f) }
    var isResolving by remember { mutableStateOf(false) }
    var resolvedMedia by remember { mutableStateOf<MediaInfo?>(null) }
    var detectedStreamUrl by remember { mutableStateOf<String?>(null) }
    var detectedPoster by remember { mutableStateOf<String?>(null) }

    // Intercept system back button
    BackHandler(enabled = true) {
        if (webViewInstance?.canGoBack() == true) {
            webViewInstance?.goBack()
        } else {
            onBack()
        }
    }

    fun openDownloadSheet() {
        val target = webViewInstance?.url ?: currentUrl
        val platform = VideoExtractorEngine.detectPlatform(target)

        if (!detectedStreamUrl.isNullOrBlank()) {
            val media = VideoExtractorEngine.createDirectStreamMedia(
                sourceUrl = target,
                directStreamUrl = detectedStreamUrl!!,
                platform = platform,
                pageTitle = pageTitle,
                thumbnail = detectedPoster
            )
            resolvedMedia = media
            return
        }

        // Otherwise resolve page URL
        isResolving = true
        coroutineScope.launch {
            try {
                val res = VideoExtractorEngine.resolveMedia(target)
                isResolving = false
                when (res) {
                    is ResolveResult.Success -> {
                        resolvedMedia = res.mediaInfo
                    }
                    is ResolveResult.Failure -> {
                        Toast.makeText(context, res.userMessage, Toast.LENGTH_SHORT).show()
                    }
                }
            } catch (t: Throwable) {
                isResolving = false
                Toast.makeText(context, "Could not extract video from page.", Toast.LENGTH_SHORT).show()
            }
        }
    }

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(SnaptubeBlack)
    ) {
        Column(modifier = Modifier.fillMaxSize()) {
            // Browser Address & Action Bar
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(SnaptubeBlack)
                    .statusBarsPadding()
                    .padding(horizontal = 8.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                IconButton(
                    onClick = {
                        if (webViewInstance?.canGoBack() == true) {
                            webViewInstance?.goBack()
                        } else {
                            onBack()
                        }
                    },
                    modifier = Modifier.size(36.dp)
                ) {
                    Icon(Icons.Default.ArrowBack, contentDescription = "Back", tint = SnaptubeTextPrimary)
                }

                IconButton(
                    onClick = { if (webViewInstance?.canGoForward() == true) webViewInstance?.goForward() },
                    modifier = Modifier.size(36.dp)
                ) {
                    Icon(Icons.Default.ArrowForward, contentDescription = "Forward", tint = SnaptubeTextPrimary)
                }

                // Current URL / Title pill
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .height(38.dp)
                        .clip(RoundedCornerShape(19.dp))
                        .background(SnaptubeCard)
                        .padding(horizontal = 12.dp),
                    contentAlignment = Alignment.CenterStart
                ) {
                    Text(
                        text = pageTitle.ifEmpty { currentUrl },
                        color = SnaptubeTextSecondary,
                        fontSize = 12.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }

                Spacer(modifier = Modifier.width(6.dp))

                IconButton(
                    onClick = { webViewInstance?.reload() },
                    modifier = Modifier.size(36.dp)
                ) {
                    Icon(Icons.Default.Refresh, contentDescription = "Reload", tint = SnaptubeTextPrimary)
                }
            }

            if (loadProgress in 0.01f..0.99f) {
                LinearProgressIndicator(
                    progress = loadProgress,
                    color = SnaptubeYellow,
                    trackColor = SnaptubeCard,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(2.dp)
                )
            }

            // WebView with Media Stream Sniffer
            AndroidView(
                modifier = Modifier.weight(1f),
                factory = { ctx ->
                    WebView(ctx).apply {
                        settings.apply {
                            javaScriptEnabled = true
                            domStorageEnabled = true
                            databaseEnabled = true
                            useWideViewPort = true
                            loadWithOverviewMode = true
                            setSupportZoom(true)
                            builtInZoomControls = false
                            displayZoomControls = false
                            mediaPlaybackRequiresUserGesture = false
                            allowFileAccess = true
                            userAgentString = "Mozilla/5.0 (Linux; Android 14; Mobile) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/128.0.0.0 Mobile Safari/537.36"
                            cacheMode = WebSettings.LOAD_DEFAULT
                        }

                        // Attach JavaScript Interface for Snaptube-grade active media sniffing
                        addJavascriptInterface(
                            VidSnapMediaBridge { url, title, poster ->
                                detectedStreamUrl = VideoExtractorEngine.cleanMediaUrl(url)
                                if (!title.isNullOrBlank()) pageTitle = title
                                if (!poster.isNullOrBlank()) detectedPoster = poster
                            },
                            "VidSnapBridge"
                        )

                        webViewClient = object : WebViewClient() {
                            override fun onPageStarted(view: WebView?, url: String?, favicon: Bitmap?) {
                                currentUrl = url.orEmpty()
                                detectedStreamUrl = null
                                view?.evaluateJavascript(SNIFFER_JS, null)
                            }

                            override fun onPageFinished(view: WebView?, url: String?) {
                                currentUrl = url.orEmpty()
                                pageTitle = view?.title.orEmpty()
                                view?.evaluateJavascript(SNIFFER_JS, null)
                            }

                            override fun shouldInterceptRequest(
                                view: WebView?,
                                request: android.webkit.WebResourceRequest?
                            ): android.webkit.WebResourceResponse? {
                                val reqUrl = request?.url?.toString().orEmpty()
                                val lower = reqUrl.lowercase()

                                val isMediaStream = (
                                    lower.contains(".mp4") ||
                                    lower.contains(".m4a") ||
                                    lower.contains(".webm") ||
                                    lower.contains("videoplayback") ||
                                    lower.contains("mime=video") ||
                                    lower.contains("video_mp4") ||
                                    (lower.contains("cdninstagram.com") && (lower.contains("&bytestart=") || lower.contains(".mp4?"))) ||
                                    (lower.contains("fbcdn.net") && (lower.contains("oe=") && lower.contains(".mp4")))
                                ) && !lower.contains(".jpg") && !lower.contains(".png") &&
                                     !lower.contains(".webp") && !lower.contains(".css") &&
                                     !lower.contains(".js") && !lower.contains("analytics")

                                if (isMediaStream) {
                                    detectedStreamUrl = VideoExtractorEngine.cleanMediaUrl(reqUrl)
                                }
                                return super.shouldInterceptRequest(view, request)
                            }
                        }

                        webChromeClient = object : WebChromeClient() {
                            override fun onProgressChanged(view: WebView?, newProgress: Int) {
                                loadProgress = newProgress / 100f
                                if (newProgress > 40) {
                                    view?.evaluateJavascript(SNIFFER_JS, null)
                                }
                            }
                            override fun onReceivedTitle(view: WebView?, title: String?) {
                                pageTitle = title.orEmpty()
                            }
                        }
                        loadUrl(initialUrl)
                        webViewInstance = this
                    }
                },
                update = { webView ->
                    if (webView.url != initialUrl && initialUrl.isNotEmpty()) {
                        webView.loadUrl(initialUrl)
                    }
                }
            )
        }

        // Floating Video Ready Indicator Pill
        AnimatedVisibility(
            visible = detectedStreamUrl != null,
            enter = fadeIn() + slideInVertically { it / 2 },
            exit = fadeOut() + slideOutVertically { it / 2 },
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .padding(end = 85.dp, bottom = 32.dp)
        ) {
            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(14.dp))
                    .background(SnaptubeYellow)
                    .clickable { openDownloadSheet() }
                    .padding(horizontal = 14.dp, vertical = 8.dp)
            ) {
                Text(
                    text = "⚡ Video Ready to Download",
                    color = SnaptubeBlack,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.ExtraBold
                )
            }
        }

        // Floating Snaptube Download Action Button
        FloatingActionButton(
            onClick = { openDownloadSheet() },
            containerColor = SnaptubeYellow,
            contentColor = SnaptubeBlack,
            shape = CircleShape,
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .padding(end = 20.dp, bottom = 24.dp)
                .size(56.dp)
        ) {
            if (isResolving) {
                CircularProgressIndicator(color = SnaptubeBlack, modifier = Modifier.size(24.dp))
            } else {
                Icon(Icons.Default.FileDownload, contentDescription = "Download Video", modifier = Modifier.size(28.dp))
            }
        }

        // Download Bottom Sheet
        resolvedMedia?.let { media ->
            DownloadBottomSheet(
                mediaInfo = media,
                onFormatSelected = { format ->
                    DownloadHelper.startDownload(context, media, format)
                    resolvedMedia = null
                },
                onDismiss = { resolvedMedia = null }
            )
        }
    }
}

