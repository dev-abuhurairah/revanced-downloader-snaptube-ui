package com.snaptube.downloader.ui.screens

import android.annotation.SuppressLint
import android.graphics.Bitmap
import android.webkit.WebChromeClient
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Toast
import androidx.activity.compose.BackHandler
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

    // Intercept system back button
    BackHandler(enabled = true) {
        if (webViewInstance?.canGoBack() == true) {
            webViewInstance?.goBack()
        } else {
            onBack()
        }
    }

    fun downloadCurrentPage() {
        val target = webViewInstance?.url ?: currentUrl
        isResolving = true

        // First attempt: inspect DOM for active video element (Instagram, TikTok, YouTube web)
        webViewInstance?.evaluateJavascript(
            """
            (function() {
                var vids = document.getElementsByTagName('video');
                for (var i = 0; i < vids.length; i++) {
                    var s = vids[i].src || '';
                    if (s.indexOf('http') === 0 && s.indexOf('blob:') !== 0) return s;
                    var sources = vids[i].getElementsByTagName('source');
                    for (var j = 0; j < sources.length; j++) {
                        var ss = sources[j].src || '';
                        if (ss.indexOf('http') === 0 && ss.indexOf('blob:') !== 0) return ss;
                    }
                }
                return '';
            })();
            """.trimIndent()
        ) { domResult ->
            val cleanDom = domResult?.trim('"', '\'', ' ', '\\')
            val effectiveStream = if (!cleanDom.isNullOrBlank() && cleanDom.startsWith("http")) {
                VideoExtractorEngine.cleanMediaUrl(cleanDom)
            } else {
                detectedStreamUrl
            }

            coroutineScope.launch {
                val res = VideoExtractorEngine.resolveMedia(
                    inputQueryOrUrl = target,
                    directStreamUrl = effectiveStream
                )
                isResolving = false
                when (res) {
                    is ResolveResult.Success -> {
                        resolvedMedia = res.mediaInfo
                    }
                    is ResolveResult.Failure -> {
                        Toast.makeText(context, res.userMessage, Toast.LENGTH_SHORT).show()
                    }
                }
            }
        }
    }

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(SnaptubeBlack)
    ) {
        Column(modifier = Modifier.fillMaxSize()) {
            // Browser Address & Action Bar with statusBarsPadding for edge-to-edge support
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
                        webViewClient = object : WebViewClient() {
                            override fun onPageStarted(view: WebView?, url: String?, favicon: Bitmap?) {
                                currentUrl = url.orEmpty()
                            }
                            override fun onPageFinished(view: WebView?, url: String?) {
                                currentUrl = url.orEmpty()
                                pageTitle = view?.title.orEmpty()
                                // Auto-sniff DOM video when page finishes loading
                                view?.evaluateJavascript(
                                    """
                                    (function() {
                                        var vids = document.getElementsByTagName('video');
                                        for (var i = 0; i < vids.length; i++) {
                                            var s = vids[i].src || '';
                                            if (s.indexOf('http') === 0 && s.indexOf('blob:') !== 0) return s;
                                            var sources = vids[i].getElementsByTagName('source');
                                            for (var j = 0; j < sources.length; j++) {
                                                var ss = sources[j].src || '';
                                                if (ss.indexOf('http') === 0 && ss.indexOf('blob:') !== 0) return ss;
                                            }
                                        }
                                        return '';
                                    })();
                                    """.trimIndent()
                                ) { domRes ->
                                    val cleanDom = domRes?.trim('"', '\'', ' ', '\\')
                                    if (!cleanDom.isNullOrBlank() && cleanDom.startsWith("http")) {
                                        detectedStreamUrl = VideoExtractorEngine.cleanMediaUrl(cleanDom)
                                    }
                                }
                            }
                            override fun shouldInterceptRequest(
                                view: WebView?,
                                request: android.webkit.WebResourceRequest?
                            ): android.webkit.WebResourceResponse? {
                                val reqUrl = request?.url?.toString().orEmpty()
                                val lower = reqUrl.lowercase()
                                if (lower.contains(".mp4") || lower.contains("videoplayback") ||
                                    lower.contains("cdninstagram.com") || lower.contains("fbcdn.net") || lower.contains("tiktokcdn.com")) {
                                    detectedStreamUrl = VideoExtractorEngine.cleanMediaUrl(reqUrl)
                                }
                                return super.shouldInterceptRequest(view, request)
                            }
                            override fun onLoadResource(view: WebView?, url: String?) {
                                super.onLoadResource(view, url)
                                val lower = url?.lowercase().orEmpty()
                                if (lower.contains(".mp4") || lower.contains("videoplayback") ||
                                    lower.contains("cdninstagram.com") || lower.contains("fbcdn.net") || lower.contains("tiktokcdn.com")) {
                                    detectedStreamUrl = VideoExtractorEngine.cleanMediaUrl(url.orEmpty())
                                }
                            }
                        }
                        webChromeClient = object : WebChromeClient() {
                            override fun onProgressChanged(view: WebView?, newProgress: Int) {
                                loadProgress = newProgress / 100f
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

        // Floating Video Ready Indicator
        if (detectedStreamUrl != null) {
            Box(
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .padding(end = 85.dp, bottom = 32.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .background(SnaptubeYellow)
                    .clickable { downloadCurrentPage() }
                    .padding(horizontal = 12.dp, vertical = 6.dp)
            ) {
                Text(
                    text = "⚡ Video Ready to Download",
                    color = SnaptubeBlack,
                    fontSize = 12.sp,
                    fontWeight = androidx.compose.ui.text.font.FontWeight.Bold
                )
            }
        }

        // Floating VidSnap Yellow Download Button
        FloatingActionButton(
            onClick = { downloadCurrentPage() },
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
