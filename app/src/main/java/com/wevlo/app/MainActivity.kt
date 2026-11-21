package com.wevlo.app

import android.annotation.SuppressLint
import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.content.pm.ActivityInfo
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Typeface
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.WindowInsets
import android.view.WindowInsetsController
import android.webkit.CookieManager
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import androidx.media3.common.MediaItem
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.hls.HlsMediaSource
import androidx.media3.exoplayer.source.MediaSource
import androidx.media3.exoplayer.source.MergingMediaSource
import androidx.media3.exoplayer.source.ProgressiveMediaSource
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import okhttp3.OkHttpClient
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import org.schabi.newpipe.extractor.NewPipe
import org.schabi.newpipe.extractor.ServiceList
import org.schabi.newpipe.extractor.downloader.Downloader
import org.schabi.newpipe.extractor.downloader.Request
import org.schabi.newpipe.extractor.downloader.Response
import org.schabi.newpipe.extractor.exceptions.ReCaptchaException
import org.schabi.newpipe.extractor.stream.AudioStream
import org.schabi.newpipe.extractor.stream.StreamInfo
import java.io.ByteArrayInputStream
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

data class VideoQualityOption(
    val label: String,
    val height: Int,
    val videoUrl: String,
    val audioUrl: String?,
    val isHls: Boolean = false
)

class MainActivity : Activity() {

    private lateinit var playerSection: LinearLayout
    private lateinit var playerView: PlayerView
    private lateinit var controlBar: LinearLayout
    private lateinit var statusText: TextView
    private lateinit var qualityBtn: Button
    private lateinit var speedBtn: Button
    private lateinit var webView: WebView

    private var player: ExoPlayer? = null
    private val executor = Executors.newSingleThreadExecutor()

    private var availableQualities: List<VideoQualityOption> = emptyList()
    private var currentQualityLabel = "Auto"
    private var currentSpeed = 1.0f
    private var isFullscreen = false

    private var lastVideoId: String? = null
    private var lastVideoTime: Long = 0L

    private val adUrlKeywords = listOf(
        "doubleclick.net",
        "googlesyndication.com",
        "googleadservices.com",
        "adservice.google.com",
        "youtube.com/pagead",
        "youtube.com/api/stats/ads",
        "youtube.com/get_midroll",
        "youtube.com/ptracking"
    )

    @SuppressLint("SetJavaScriptEnabled")
    @OptIn(UnstableApi::class)
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        NewPipe.init(OkHttpDownloader.getInstance())

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.parseColor("#0F0F0F"))
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
        }

        // ১. অ্যাড-ফ্রি ExoPlayer সেকশন (ভিডিও ক্লিক করলে দৃশ্যমান হবে)
        playerSection = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.BLACK)
            visibility = View.GONE
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
        }

        val playerHeight = (resources.displayMetrics.widthPixels * 9) / 16
        playerView = PlayerView(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                playerHeight
            )
            resizeMode = AspectRatioFrameLayout.RESIZE_MODE_FIT
            setShowBuffering(PlayerView.SHOW_BUFFERING_WHEN_PLAYING)
            setShowNextButton(false)
            setShowPreviousButton(false)
            setFullscreenButtonClickListener { shouldEnterFullscreen ->
                toggleFullscreen(shouldEnterFullscreen)
            }
        }

        player = ExoPlayer.Builder(this)
            .setSeekBackIncrementMs(10_000L)
            .setSeekForwardIncrementMs(10_000L)
            .build()
        playerView.player = player

        // ২. প্লেয়ারের নিচের কন্ট্রোল বার (টাইটেল + Quality + Speed + Close)
        controlBar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setBackgroundColor(Color.parseColor("#181818"))
            setPadding(16, 10, 16, 10)
        }

        statusText = TextView(this).apply {
            text = "Loading..."
            setTextColor(Color.WHITE)
            textSize = 13f
            maxLines = 2
            setTypeface(null, Typeface.BOLD)
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
                setMargins(0, 0, 12, 0)
            }
        }

        qualityBtn = Button(this).apply {
            text = "Quality"
            textSize = 11f
            setPadding(18, 6, 18, 6)
            setOnClickListener { showQualityDialog() }
        }

        speedBtn = Button(this).apply {
            text = "1.0x"
            textSize = 11f
            setPadding(18, 6, 18, 6)
            setOnClickListener { showSpeedDialog() }
        }

        val closeBtn = Button(this).apply {
            text = "✕"
            textSize = 12f
            setPadding(18, 6, 18, 6)
            setOnClickListener { closePlayer() }
        }

        controlBar.addView(statusText)
        controlBar.addView(qualityBtn)
        controlBar.addView(speedBtn)
        controlBar.addView(closeBtn)

        playerSection.addView(playerView)
        playerSection.addView(controlBar)

        // ৩. নিচে আসল YouTube UI (WebView)
        webView = WebView(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                0,
                1f
            )
            settings.apply {
                javaScriptEnabled = true
                domStorageEnabled = true
                databaseEnabled = true
                javaScriptCanOpenWindowsAutomatically = false
                setSupportMultipleWindows(false)
            }

            val cm = CookieManager.getInstance()
            cm.setAcceptCookie(true)
            cm.setAcceptThirdPartyCookies(this, true)

            webViewClient = object : WebViewClient() {
                override fun shouldOverrideUrlLoading(
                    view: WebView?,
                    request: WebResourceRequest?
                ): Boolean {
                    val url = request?.url?.toString() ?: return false
                    val videoId = extractYouTubeVideoId(url)
                    if (videoId != null) {
                        handleVideoClick(videoId, view, fromSpa = false)
                        return true
                    }
                    return false
                }

                override fun doUpdateVisitedHistory(
                    view: WebView?,
                    url: String?,
                    isReload: Boolean
                ) {
                    super.doUpdateVisitedHistory(view, url, isReload)
                    if (url != null) {
                        val videoId = extractYouTubeVideoId(url)
                        if (videoId != null) {
                            handleVideoClick(videoId, view, fromSpa = true)
                        }
                    }
                }

                override fun shouldInterceptRequest(
                    view: WebView?,
                    request: WebResourceRequest?
                ): WebResourceResponse? {
                    val url = request?.url?.toString()?.lowercase() ?: return null
                    if (adUrlKeywords.any { url.contains(it) }) {
                        return WebResourceResponse(
                            "text/plain",
                            "utf-8",
                            ByteArrayInputStream(ByteArray(0))
                        )
                    }
                    return super.shouldInterceptRequest(view, request)
                }

                override fun onPageStarted(view: WebView?, url: String?, favicon: Bitmap?) {
                    super.onPageStarted(view, url, favicon)
                    injectFeedAdHideCss(view)
                }

                override fun onPageFinished(view: WebView?, url: String?) {
                    super.onPageFinished(view, url)
                    injectFeedAdHideCss(view)
                }
            }
        }

        root.addView(playerSection)
        root.addView(webView)
        setContentView(root)

        webView.loadUrl("https://m.youtube.com")
        handleIncomingIntent(intent)
    }

    override fun onNewIntent(intent: Intent?) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleIncomingIntent(intent)
    }

    private fun handleIncomingIntent(intent: Intent?) {
        if (intent == null) return
        val text = when (intent.action) {
            Intent.ACTION_SEND -> intent.getStringExtra(Intent.EXTRA_TEXT)
            Intent.ACTION_VIEW -> intent.dataString
            else -> null
        } ?: return

        val urlMatch = Regex("https?://\\S+").find(text)?.value ?: text
        val videoId = extractYouTubeVideoId(urlMatch)
        if (videoId != null) {
            playYouTubeVideo("https://www.youtube.com/watch?v=$videoId")
        }
    }

    private fun extractYouTubeVideoId(url: String): String? {
        val uri = Uri.parse(url)
        val host = uri.host?.lowercase() ?: return null
        if (!host.contains("youtube.com") && !host.contains("youtu.be")) return null

        if (host.contains("youtu.be")) {
            return uri.pathSegments.firstOrNull()?.takeIf { it.isNotBlank() }
        }
        val vParam = uri.getQueryParameter("v")
        if (!vParam.isNullOrBlank()) return vParam

        val segments = uri.pathSegments
        if (segments.size >= 2 &&
            (segments[0] == "shorts" || segments[0] == "embed" || segments[0] == "live")
        ) {
            return segments[1].takeIf { it.isNotBlank() }
        }
        return null
    }

    private fun handleVideoClick(videoId: String, view: WebView?, fromSpa: Boolean) {
        val now = System.currentTimeMillis()
        if (videoId == lastVideoId && now - lastVideoTime < 2000L) return
        lastVideoId = videoId
        lastVideoTime = now

        // WebView-এর নিজস্ব প্লেয়ার বন্ধ করে ফিডে ফেরত আনা
        view?.evaluateJavascript(
            "document.querySelectorAll('video').forEach(function(v){v.pause();v.muted=true;v.removeAttribute('src');v.load();});",
            null
        )
        if (fromSpa) {
            if (view?.canGoBack() == true) {
                view.goBack()
            } else {
                view?.loadUrl("https://m.youtube.com")
            }
        }

        playYouTubeVideo("https://www.youtube.com/watch?v=$videoId")
    }

    private fun injectFeedAdHideCss(view: WebView?) {
        val js = """
            (function() {
                if (document.getElementById('__yt_feed_hide')) return;
                if (!document.head) return;
                var s = document.createElement('style');
                s.id = '__yt_feed_hide';
                s.innerHTML = 'ytm-ad-slot-renderer, ytm-in-feed-ad-layout-renderer, ytm-promoted-sparkles-web-renderer, ytm-promoted-video-renderer, ad-slot-renderer { display: none !important; }';
                document.head.appendChild(s);
            })();
        """.trimIndent()
        view?.evaluateJavascript(js, null)
    }

    @OptIn(UnstableApi::class)
    private fun playYouTubeVideo(watchUrl: String) {
        playerSection.visibility = View.VISIBLE
        statusText.text = "Loading video (Ad-Free)..."
        qualityBtn.text = "Quality"

        executor.execute {
            try {
                val info = StreamInfo.getInfo(ServiceList.YouTube, watchUrl)
                val bestAudio: AudioStream? = info.audioStreams
                    .filter { it.isUrl }
                    .maxByOrNull { it.averageBitrate }

                val options = mutableListOf<VideoQualityOption>()

                // Adaptive Video + Audio (1080p, 720p, 480p, 360p...)
                if (bestAudio != null) {
                    val seenHeights = HashSet<Int>()
                    val sortedVideoOnly = info.videoOnlyStreams
                        .filter { it.isUrl }
                        .sortedByDescending { parseHeight(it.resolution) }

                    for (vs in sortedVideoOnly) {
                        val h = parseHeight(vs.resolution)
                        if (h > 0 && seenHeights.add(h)) {
                            options.add(
                                VideoQualityOption(
                                    label = "${h}p",
                                    height = h,
                                    videoUrl = vs.content,
                                    audioUrl = bestAudio.content
                                )
                            )
                        }
                    }
                }

                // Muxed fallback (সাধারণত 360p)
                for (ms in info.videoStreams.filter { it.isUrl }) {
                    val h = parseHeight(ms.resolution)
                    val label = if (h > 0) "${h}p (Muxed)" else ms.resolution
                    if (options.none { it.height == h }) {
                        options.add(
                            VideoQualityOption(
                                label = label,
                                height = h,
                                videoUrl = ms.content,
                                audioUrl = null
                            )
                        )
                    }
                }

                // Live / HLS fallback
                if (!info.hlsUrl.isNullOrEmpty()) {
                    options.add(
                        VideoQualityOption(
                            label = "Auto (HLS)",
                            height = 0,
                            videoUrl = info.hlsUrl,
                            audioUrl = null,
                            isHls = true
                        )
                    )
                }

                val defaultOption = options.firstOrNull { it.height == 720 }
                    ?: options.firstOrNull { it.height == 480 }
                    ?: options.firstOrNull()

                runOnUiThread {
                    availableQualities = options
                    if (defaultOption != null) {
                        statusText.text = info.name
                        startOptionInPlayer(defaultOption, resumePositionMs = 0L)
                    } else {
                        statusText.text = "ভিডিও স্ট্রিম পাওয়া যায়নি"
                    }
                }
            } catch (e: Exception) {
                runOnUiThread {
                    statusText.text = "Error: ${e.localizedMessage}"
                }
            }
        }
    }

    private fun parseHeight(resolution: String?): Int {
        if (resolution.isNullOrBlank()) return 0
        val digits = Regex("(\\d+)").find(resolution)?.groupValues?.get(1)
        return digits?.toIntOrNull() ?: 0
    }

    @OptIn(UnstableApi::class)
    private fun startOptionInPlayer(option: VideoQualityOption, resumePositionMs: Long) {
        val dataSourceFactory = DefaultHttpDataSource.Factory()
            .setUserAgent("Mozilla/5.0 (Windows NT 10.0; Win64; x64; rv:140.0) Gecko/20100101 Firefox/140.0")

        val mediaSource: MediaSource = if (option.isHls) {
            HlsMediaSource.Factory(dataSourceFactory)
                .createMediaSource(MediaItem.fromUri(option.videoUrl))
        } else if (option.audioUrl != null) {
            val videoSource = ProgressiveMediaSource.Factory(dataSourceFactory)
                .createMediaSource(MediaItem.fromUri(option.videoUrl))
            val audioSource = ProgressiveMediaSource.Factory(dataSourceFactory)
                .createMediaSource(MediaItem.fromUri(option.audioUrl))
            MergingMediaSource(videoSource, audioSource)
        } else {
            ProgressiveMediaSource.Factory(dataSourceFactory)
                .createMediaSource(MediaItem.fromUri(option.videoUrl))
        }

        currentQualityLabel = option.label
        qualityBtn.text = option.label

        player?.apply {
            setMediaSource(mediaSource)
            prepare()
            if (resumePositionMs > 0L) seekTo(resumePositionMs)
            setPlaybackSpeed(currentSpeed)
            playWhenReady = true
        }
    }

    private fun showQualityDialog() {
        if (availableQualities.isEmpty()) return
        val labels = availableQualities.map { it.label }.toTypedArray()
        val checked = availableQualities.indexOfFirst { it.label == currentQualityLabel }

        AlertDialog.Builder(this)
            .setTitle("Select Video Quality")
            .setSingleChoiceItems(labels, checked) { dialog, which ->
                val selected = availableQualities[which]
                val pos = player?.currentPosition ?: 0L
                startOptionInPlayer(selected, resumePositionMs = pos)
                dialog.dismiss()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun showSpeedDialog() {
        val speeds = floatArrayOf(0.5f, 0.75f, 1.0f, 1.25f, 1.5f, 2.0f)
        val labels = arrayOf("0.5x", "0.75x", "1.0x (Normal)", "1.25x", "1.5x", "2.0x")
        val checked = speeds.indexOfFirst { it == currentSpeed }

        AlertDialog.Builder(this)
            .setTitle("Playback Speed")
            .setSingleChoiceItems(labels, checked) { dialog, which ->
                currentSpeed = speeds[which]
                speedBtn.text = "${currentSpeed}x"
                player?.setPlaybackSpeed(currentSpeed)
                dialog.dismiss()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun toggleFullscreen(enter: Boolean) {
        isFullscreen = enter
        if (enter) {
            requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
            webView.visibility = View.GONE
            controlBar.visibility = View.GONE
            playerView.layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
            playerSection.layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
            setSystemUiHidden(true)
        } else {
            requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
            val normalHeight = (resources.displayMetrics.widthPixels.coerceAtMost(
                resources.displayMetrics.heightPixels
            ) * 9) / 16
            playerSection.layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
            playerView.layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                normalHeight
            )
            controlBar.visibility = View.VISIBLE
            webView.visibility = View.VISIBLE
            setSystemUiHidden(false)
        }
    }

    @Suppress("DEPRECATION")
    private fun setSystemUiHidden(hide: Boolean) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val controller = window.insetsController ?: return
            if (hide) {
                controller.hide(WindowInsets.Type.statusBars() or WindowInsets.Type.navigationBars())
                controller.systemBarsBehavior =
                    WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            } else {
                controller.show(WindowInsets.Type.statusBars() or WindowInsets.Type.navigationBars())
            }
        } else {
            window.decorView.systemUiVisibility = if (hide) {
                View.SYSTEM_UI_FLAG_FULLSCREEN or
                    View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or
                    View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
            } else {
                View.SYSTEM_UI_FLAG_VISIBLE
            }
        }
    }

    private fun closePlayer() {
        if (isFullscreen) toggleFullscreen(false)
        player?.stop()
        playerSection.visibility = View.GONE
    }

    @Suppress("DEPRECATION")
    override fun onBackPressed() {
        if (isFullscreen) {
            toggleFullscreen(false)
        } else if (webView.canGoBack()) {
            webView.goBack()
        } else if (playerSection.visibility == View.VISIBLE) {
            closePlayer()
        } else {
            super.onBackPressed()
        }
    }

    override fun onStop() {
        super.onStop()
        player?.pause()
    }

    override fun onDestroy() {
        player?.release()
        player = null
        executor.shutdownNow()
        super.onDestroy()
    }
}

class OkHttpDownloader private constructor() : Downloader() {

    private val client = OkHttpClient.Builder()
        .readTimeout(30, TimeUnit.SECONDS)
        .build()

    override fun execute(request: Request): Response {
        val body: RequestBody? = request.dataToSend()?.toRequestBody(null)
        val builder = okhttp3.Request.Builder()
            .method(request.httpMethod(), body)
            .url(request.url())

        for ((name, values) in request.headers()) {
            builder.removeHeader(name)
            for (value in values) {
                builder.addHeader(name, value)
            }
        }

        if (request.headers()["User-Agent"].isNullOrEmpty()) {
            builder.header(
                "User-Agent",
                "Mozilla/5.0 (Windows NT 10.0; Win64; x64; rv:140.0) Gecko/20100101 Firefox/140.0"
            )
        }

        val okResponse = client.newCall(builder.build()).execute()
        if (okResponse.code == 429) {
            okResponse.close()
            throw ReCaptchaException("reCaptcha Challenge requested", request.url())
        }

        val responseBody = okResponse.body?.string()
        val latestUrl = okResponse.request.url.toString()

        return Response(
            okResponse.code,
            okResponse.message,
            okResponse.headers.toMultimap(),
            responseBody,
            latestUrl
        )
    }

    companion object {
        @Volatile
        private var instance: OkHttpDownloader? = null

        fun getInstance(): OkHttpDownloader {
            return instance ?: synchronized(this) {
                instance ?: OkHttpDownloader().also { instance = it }
            }
        }
    }
}