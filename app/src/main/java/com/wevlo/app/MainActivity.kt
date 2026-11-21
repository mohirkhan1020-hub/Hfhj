package com.wevlo.app

import android.Manifest
import android.annotation.SuppressLint
import android.app.Activity
import android.app.AlertDialog
import android.app.DownloadManager
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.PictureInPictureParams
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ActivityInfo
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Typeface
import android.media.AudioManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.util.Rational
import android.view.GestureDetector
import android.view.Gravity
import android.view.MotionEvent
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
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
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
import org.json.JSONArray
import org.json.JSONObject
import org.schabi.newpipe.extractor.NewPipe
import org.schabi.newpipe.extractor.ServiceList
import org.schabi.newpipe.extractor.downloader.Downloader
import org.schabi.newpipe.extractor.downloader.Request
import org.schabi.newpipe.extractor.downloader.Response
import org.schabi.newpipe.extractor.exceptions.ReCaptchaException
import org.schabi.newpipe.extractor.stream.AudioStream
import org.schabi.newpipe.extractor.stream.StreamInfo
import java.io.ByteArrayInputStream
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.math.abs

data class VideoQualityOption(
    val label: String,
    val height: Int,
    val videoUrl: String,
    val audioUrl: String?,
    val isHls: Boolean = false
)

data class SponsorSegment(
    val startMs: Long,
    val endMs: Long,
    val category: String
)

class MainActivity : Activity() {

    private lateinit var topMenuBar: LinearLayout
    private lateinit var shortsToggleBtn: Button
    private lateinit var playerSection: LinearLayout
    private lateinit var playerFrame: FrameLayout
    private lateinit var playerView: PlayerView
    private lateinit var gestureBadge: TextView
    private lateinit var controlBar1: LinearLayout
    private lateinit var controlBar2: LinearLayout
    private lateinit var statusText: TextView
    private lateinit var statsText: TextView
    private lateinit var qualityBtn: Button
    private lateinit var speedBtn: Button
    private lateinit var bgPlayBtn: Button
    private lateinit var webView: WebView

    private var player: ExoPlayer? = null
    private val executor = Executors.newFixedThreadPool(3)
    private val mainHandler = Handler(Looper.getMainLooper())

    private var availableQualities: List<VideoQualityOption> = emptyList()
    private var currentQualityLabel = "Auto"
    private var currentSpeed = 1.0f
    private var isFullscreen = false
    private var bgPlayEnabled = true
    private var blockShortsEnabled = false

    private var currentVideoId: String? = null
    private var currentVideoTitle: String = "AdFree Tube"
    private var bestMuxedDownloadUrl: String? = null
    private var bestAudioDownloadUrl: String? = null

    @Volatile
    private var sponsorSegments: List<SponsorSegment> = emptyList()
    private var lastSkippedEndMs: Long = -1L

    private var lastVideoId: String? = null
    private var lastVideoTime: Long = 0L

    private var brightnessRatio = 0.5f
    private var volumeRatio = 0.5f

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

    private val sponsorCheckRunnable = object : Runnable {
        override fun run() {
            val p = player
            if (p != null && p.isPlaying) {
                val pos = p.currentPosition
                for (seg in sponsorSegments) {
                    if (pos in seg.startMs until (seg.endMs - 400) && lastSkippedEndMs != seg.endMs) {
                        lastSkippedEndMs = seg.endMs
                        p.seekTo(seg.endMs)
                        showGestureBadge("Skipped ${seg.category}")
                        break
                    }
                }
            }
            mainHandler.postDelayed(this, 500)
        }
    }

    @SuppressLint("SetJavaScriptEnabled", "ClickableViewAccessibility")
    @OptIn(UnstableApi::class)
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        NewPipe.init(OkHttpDownloader.getInstance())

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
                requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 101)
            }
        }

        val audioManager = getSystemService(Context.AUDIO_SERVICE) as AudioManager
        val maxVol = audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC).coerceAtLeast(1)
        volumeRatio = audioManager.getStreamVolume(AudioManager.STREAM_MUSIC).toFloat() / maxVol

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.parseColor("#0F0F0F"))
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
        }

        // ১. উপরের টুলবার (Home, History, Shorts Toggle)
        topMenuBar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setBackgroundColor(Color.parseColor("#181818"))
            setPadding(12, 8, 12, 8)
        }

        val homeBtn = Button(this).apply {
            text = "Home"
            textSize = 11f
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            setOnClickListener { webView.loadUrl("https://m.youtube.com") }
        }

        val historyBtn = Button(this).apply {
            text = "History"
            textSize = 11f
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            setOnClickListener { showHistoryDialog() }
        }

        shortsToggleBtn = Button(this).apply {
            text = "Shorts: ON"
            textSize = 11f
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1.2f)
            setOnClickListener {
                blockShortsEnabled = !blockShortsEnabled
                text = if (blockShortsEnabled) "Shorts: OFF" else "Shorts: ON"
                injectYouTubeCss(webView)
            }
        }

        topMenuBar.addView(homeBtn)
        topMenuBar.addView(historyBtn)
        topMenuBar.addView(shortsToggleBtn)

        // ২. অ্যাড-ফ্রি ExoPlayer সেকশন
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
        playerFrame = FrameLayout(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                playerHeight
            )
        }

        playerView = PlayerView(this).apply {
            layoutParams = FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
            resizeMode = AspectRatioFrameLayout.RESIZE_MODE_FIT
            setShowBuffering(PlayerView.SHOW_BUFFERING_WHEN_PLAYING)
            setShowNextButton(false)
            setShowPreviousButton(false)
            setFullscreenButtonClickListener { shouldEnter ->
                toggleFullscreen(shouldEnter)
            }
        }

        gestureBadge = TextView(this).apply {
            visibility = View.GONE
            setTextColor(Color.WHITE)
            setBackgroundColor(Color.parseColor("#AA000000"))
            textSize = 15f
            setTypeface(null, Typeface.BOLD)
            setPadding(28, 14, 28, 14)
            layoutParams = FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.CENTER
            )
        }

        playerFrame.addView(playerView)
        playerFrame.addView(gestureBadge)

        player = ExoPlayer.Builder(this)
            .setSeekBackIncrementMs(10_000L)
            .setSeekForwardIncrementMs(10_000L)
            .build()
        playerView.player = player

        setupPlayerGestures()
        setupBackgroundCallbacks()

        // ৩. প্লেয়ারের নিচের টাইটেল ও লাইক/ডিসলাইক বার
        controlBar1 = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.parseColor("#181818"))
            setPadding(20, 12, 20, 4)
        }

        statusText = TextView(this).apply {
            text = "Loading..."
            setTextColor(Color.WHITE)
            textSize = 14f
            maxLines = 2
            setTypeface(null, Typeface.BOLD)
        }

        statsText = TextView(this).apply {
            text = "0% Ads  •  SponsorBlock Ready"
            setTextColor(Color.parseColor("#AAAAAA"))
            textSize = 12f
            setPadding(0, 4, 0, 4)
        }

        controlBar1.addView(statusText)
        controlBar1.addView(statsText)

        // ৪. প্লেয়ার অ্যাকশন বাটন বার (Quality, Speed, PiP, BG, Save, Close)
        controlBar2 = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setBackgroundColor(Color.parseColor("#202020"))
            setPadding(8, 4, 8, 8)
        }

        qualityBtn = makeSmallBtn("Quality") { showQualityDialog() }
        speedBtn = makeSmallBtn("1.0x") { showSpeedDialog() }
        val pipBtn = makeSmallBtn("PiP") { enterPipMode() }
        bgPlayBtn = makeSmallBtn("BG:ON") {
            bgPlayEnabled = !bgPlayEnabled
            bgPlayBtn.text = if (bgPlayEnabled) "BG:ON" else "BG:OFF"
        }
        val saveBtn = makeSmallBtn("Save") { showDownloadDialog() }
        val closeBtn = makeSmallBtn("✕") { closePlayer() }

        controlBar2.addView(qualityBtn)
        controlBar2.addView(speedBtn)
        controlBar2.addView(pipBtn)
        controlBar2.addView(bgPlayBtn)
        controlBar2.addView(saveBtn)
        controlBar2.addView(closeBtn)

        playerSection.addView(playerFrame)
        playerSection.addView(controlBar1)
        playerSection.addView(controlBar2)

        // ৫. নিচে আসল YouTube UI (WebView)
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
                        if (blockShortsEnabled && url.contains("/shorts/")) {
                            Toast.makeText(this@MainActivity, "Shorts Blocked", Toast.LENGTH_SHORT).show()
                            return true
                        }
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
                            if (blockShortsEnabled && url.contains("/shorts/")) {
                                if (view?.canGoBack() == true) view.goBack()
                                Toast.makeText(this@MainActivity, "Shorts Blocked", Toast.LENGTH_SHORT).show()
                                return
                            }
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
                        return WebResourceResponse("text/plain", "utf-8", ByteArrayInputStream(ByteArray(0)))
                    }
                    return super.shouldInterceptRequest(view, request)
                }

                override fun onPageStarted(view: WebView?, url: String?, favicon: Bitmap?) {
                    super.onPageStarted(view, url, favicon)
                    injectYouTubeCss(view)
                }

                override fun onPageFinished(view: WebView?, url: String?) {
                    super.onPageFinished(view, url)
                    injectYouTubeCss(view)
                }
            }
        }

        root.addView(topMenuBar)
        root.addView(playerSection)
        root.addView(webView)
        setContentView(root)

        webView.loadUrl("https://m.youtube.com")
        handleIncomingIntent(intent)
        mainHandler.postDelayed(sponsorCheckRunnable, 500)
    }

    private fun makeSmallBtn(label: String, onClick: () -> Unit): Button {
        return Button(this).apply {
            text = label
            textSize = 10f
            setPadding(4, 4, 4, 4)
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            setOnClickListener { onClick() }
        }
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun setupPlayerGestures() {
        val gestureDetector = GestureDetector(this, object : GestureDetector.SimpleOnGestureListener() {
            override fun onDoubleTap(e: MotionEvent): Boolean {
                val p = player ?: return false
                if (e.x < playerView.width / 2f) {
                    p.seekBack()
                    showGestureBadge("<< 10s")
                } else {
                    p.seekForward()
                    showGestureBadge("10s >>")
                }
                return true
            }

            override fun onScroll(
                e1: MotionEvent?,
                e2: MotionEvent,
                distanceX: Float,
                distanceY: Float
            ): Boolean {
                if (e1 == null || abs(distanceY) < abs(distanceX)) return false
                val w = playerView.width.toFloat().coerceAtLeast(1f)
                val h = playerView.height.toFloat().coerceAtLeast(1f)
                val delta = distanceY / h

                if (e1.x < w / 2f) {
                    brightnessRatio = (brightnessRatio + delta).coerceIn(0.05f, 1.0f)
                    val lp = window.attributes
                    lp.screenBrightness = brightnessRatio
                    window.attributes = lp
                    showGestureBadge("Brightness: ${(brightnessRatio * 100).toInt()}%")
                } else {
                    val audioManager = getSystemService(Context.AUDIO_SERVICE) as AudioManager
                    val maxVol = audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC).coerceAtLeast(1)
                    volumeRatio = (volumeRatio + delta).coerceIn(0.0f, 1.0f)
                    val newVol = (volumeRatio * maxVol).toInt()
                    audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, newVol, 0)
                    showGestureBadge("Volume: ${(volumeRatio * 100).toInt()}%")
                }
                return true
            }
        })

        playerView.setOnTouchListener { _, event ->
            gestureDetector.onTouchEvent(event)
            false
        }
    }

    private fun showGestureBadge(text: String) {
        gestureBadge.text = text
        gestureBadge.visibility = View.VISIBLE
        mainHandler.removeCallbacks(hideBadgeRunnable)
        mainHandler.postDelayed(hideBadgeRunnable, 900)
    }

    private val hideBadgeRunnable = Runnable {
        gestureBadge.visibility = View.GONE
    }

    private fun setupBackgroundCallbacks() {
        BgPlaybackService.onTogglePlayPause = {
            mainHandler.post {
                val p = player
                if (p != null) {
                    if (p.isPlaying) p.pause() else p.play()
                    updateBgNotificationState()
                }
            }
        }
        BgPlaybackService.onStopPlayback = {
            mainHandler.post {
                player?.pause()
                stopService(Intent(this, BgPlaybackService::class.java))
            }
        }
    }

    private fun updateBgNotificationState() {
        val isPlaying = player?.isPlaying == true
        val intent = Intent(this, BgPlaybackService::class.java).apply {
            action = BgPlaybackService.ACTION_UPDATE
            putExtra(BgPlaybackService.EXTRA_TITLE, currentVideoTitle)
            putExtra(BgPlaybackService.EXTRA_PLAYING, isPlaying)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(intent)
        } else {
            startService(intent)
        }
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
            playYouTubeVideo(videoId)
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
        playYouTubeVideo(videoId)
    }

    private fun injectYouTubeCss(view: WebView?) {
        val shortsCss = if (blockShortsEnabled) {
            "ytm-reel-shelf-renderer, ytm-shorts-lockup-view-model, [is-shorts] { display: none !important; }"
        } else {
            ""
        }
        val js = """
            (function() {
                if (!document.head) return;
                var existing = document.getElementById('__yt_feed_hide');
                if (existing) existing.remove();
                var s = document.createElement('style');
                s.id = '__yt_feed_hide';
                s.innerHTML = 'ytm-ad-slot-renderer, ytm-in-feed-ad-layout-renderer, ytm-promoted-sparkles-web-renderer, ytm-promoted-video-renderer, ad-slot-renderer { display: none !important; } $shortsCss';
                document.head.appendChild(s);
            })();
        """.trimIndent()
        view?.evaluateJavascript(js, null)
    }

    @OptIn(UnstableApi::class)
    private fun playYouTubeVideo(videoId: String) {
        currentVideoId = videoId
        sponsorSegments = emptyList()
        lastSkippedEndMs = -1L
        bestMuxedDownloadUrl = null
        bestAudioDownloadUrl = null

        playerSection.visibility = View.VISIBLE
        statusText.text = "Loading video (Ad-Free)..."
        statsText.text = "Fetching streams & SponsorBlock..."
        qualityBtn.text = "Quality"

        val watchUrl = "https://www.youtube.com/watch?v=$videoId"

        // ১. মূল স্ট্রিম বের করা
        executor.execute {
            try {
                val info = StreamInfo.getInfo(ServiceList.YouTube, watchUrl)
                val bestAudio: AudioStream? = info.audioStreams
                    .filter { it.isUrl }
                    .maxByOrNull { it.averageBitrate }

                bestAudioDownloadUrl = bestAudio?.content
                bestMuxedDownloadUrl = info.videoStreams.firstOrNull { it.isUrl }?.content

                val options = mutableListOf<VideoQualityOption>()
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

                for (ms in info.videoStreams.filter { it.isUrl }) {
                    val h = parseHeight(ms.resolution)
                    val label = if (h > 0) "${h}p (Muxed)" else ms.resolution
                    if (options.none { it.height == h }) {
                        options.add(VideoQualityOption(label, h, ms.content, null))
                    }
                }

                if (!info.hlsUrl.isNullOrEmpty()) {
                    options.add(VideoQualityOption("Auto (HLS)", 0, info.hlsUrl, null, isHls = true))
                }

                val defaultOption = options.firstOrNull { it.height == 720 }
                    ?: options.firstOrNull { it.height == 480 }
                    ?: options.firstOrNull()

                runOnUiThread {
                    availableQualities = options
                    if (defaultOption != null) {
                        currentVideoTitle = info.name
                        statusText.text = info.name
                        saveToHistory(videoId, info.name)
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

        // ২. SponsorBlock + Return YouTube Dislike লোড করা (প্যারালাল)
        executor.execute {
            val segs = fetchSponsorSegments(videoId)
            sponsorSegments = segs
            val votesText = fetchDislikeStats(videoId)
            runOnUiThread {
                val sbInfo = if (segs.isNotEmpty()) "SponsorBlock: ${segs.size} skips" else "SponsorBlock: 0"
                statsText.text = if (votesText != null) "$votesText  •  $sbInfo" else "0% Ads  •  $sbInfo"
            }
        }
    }

    private fun fetchSponsorSegments(videoId: String): List<SponsorSegment> {
        val list = mutableListOf<SponsorSegment>()
        try {
            val apiUrl = "https://sponsor.ajay.app/api/skipSegments?videoID=$videoId&categories=%5B%22sponsor%22,%22selfpromo%22,%22interaction%22,%22intro%22,%22outro%22%5D"
            val conn = (URL(apiUrl).openConnection() as HttpURLConnection).apply {
                connectTimeout = 4000
                readTimeout = 4000
            }
            if (conn.responseCode == 200) {
                val body = conn.inputStream.bufferedReader().readText()
                val arr = JSONArray(body)
                for (i in 0 until arr.length()) {
                    val obj = arr.getJSONObject(i)
                    val seg = obj.getJSONArray("segment")
                    val startMs = (seg.getDouble(0) * 1000).toLong()
                    val endMs = (seg.getDouble(1) * 1000).toLong()
                    val cat = obj.optString("category", "sponsor")
                    if (endMs > startMs + 500) {
                        list.add(SponsorSegment(startMs, endMs, cat))
                    }
                }
            }
            conn.disconnect()
        } catch (_: Exception) {
        }
        return list
    }

    private fun fetchDislikeStats(videoId: String): String? {
        return try {
            val conn = (URL("https://returnyoutubedislikeapi.com/votes?videoId=$videoId").openConnection() as HttpURLConnection).apply {
                connectTimeout = 4000
                readTimeout = 4000
            }
            if (conn.responseCode == 200) {
                val obj = JSONObject(conn.inputStream.bufferedReader().readText())
                val likes = formatCount(obj.optLong("likes", 0))
                val dislikes = formatCount(obj.optLong("dislikes", 0))
                conn.disconnect()
                "👍 $likes   👎 $dislikes"
            } else {
                conn.disconnect()
                null
            }
        } catch (_: Exception) {
            null
        }
    }

    private fun formatCount(n: Long): String {
        return when {
            n >= 1_000_000 -> String.format("%.1fM", n / 1_000_000.0)
            n >= 1_000 -> String.format("%.1fK", n / 1_000.0)
            else -> n.toString()
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

    private fun showDownloadDialog() {
        val items = mutableListOf<String>()
        val urls = mutableListOf<Pair<String, String>>()

        bestMuxedDownloadUrl?.let {
            items.add("Download Video (MP4)")
            urls.add(it to "mp4")
        }
        bestAudioDownloadUrl?.let {
            items.add("Download Audio Only (M4A)")
            urls.add(it to "m4a")
        }

        if (items.isEmpty()) {
            Toast.makeText(this, "Download stream পাওয়া যায়নি", Toast.LENGTH_SHORT).show()
            return
        }

        AlertDialog.Builder(this)
            .setTitle("Download Offline")
            .setItems(items.toTypedArray()) { _, which ->
                val (url, ext) = urls[which]
                startSystemDownload(url, ext)
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun startSystemDownload(url: String, ext: String) {
        try {
            val safeName = currentVideoTitle.replace(Regex("[^a-zA-Z0-9._ -]"), "_").take(50)
            val fileName = "${safeName}_${System.currentTimeMillis()}.$ext"
            val req = DownloadManager.Request(Uri.parse(url))
                .setTitle(currentVideoTitle)
                .setDescription("Downloading $ext...")
                .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
                .setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, fileName)

            val dm = getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
            dm.enqueue(req)
            Toast.makeText(this, "Downloading to Downloads/$fileName", Toast.LENGTH_LONG).show()
        } catch (e: Exception) {
            Toast.makeText(this, "Download error: ${e.localizedMessage}", Toast.LENGTH_SHORT).show()
        }
    }

    private fun saveToHistory(videoId: String, title: String) {
        val prefs = getSharedPreferences("yt_history", Context.MODE_PRIVATE)
        val arr = try {
            JSONArray(prefs.getString("items", "[]"))
        } catch (_: Exception) {
            JSONArray()
        }
        val newArr = JSONArray()
        newArr.put(JSONObject().apply {
            put("id", videoId)
            put("title", title)
        })
        for (i in 0 until arr.length()) {
            val item = arr.optJSONObject(i) ?: continue
            if (item.optString("id") != videoId && newArr.length() < 30) {
                newArr.put(item)
            }
        }
        prefs.edit().putString("items", newArr.toString()).apply()
    }

    private fun showHistoryDialog() {
        val prefs = getSharedPreferences("yt_history", Context.MODE_PRIVATE)
        val arr = try {
            JSONArray(prefs.getString("items", "[]"))
        } catch (_: Exception) {
            JSONArray()
        }
        if (arr.length() == 0) {
            Toast.makeText(this, "History খালি আছে", Toast.LENGTH_SHORT).show()
            return
        }
        val titles = Array(arr.length()) { i -> arr.getJSONObject(i).getString("title") }
        AlertDialog.Builder(this)
            .setTitle("Watch History")
            .setItems(titles) { _, which ->
                val id = arr.getJSONObject(which).getString("id")
                playYouTubeVideo(id)
            }
            .setNeutralButton("Clear") { _, _ ->
                prefs.edit().remove("items").apply()
            }
            .setNegativeButton("Close", null)
            .show()
    }

    private fun enterPipMode() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val params = PictureInPictureParams.Builder()
                .setAspectRatio(Rational(16, 9))
                .build()
            enterPictureInPictureMode(params)
        } else {
            Toast.makeText(this, "PiP requires Android 8.0+", Toast.LENGTH_SHORT).show()
        }
    }

    override fun onPictureInPictureModeChanged(
        isInPictureInPictureMode: Boolean,
        newConfig: Configuration
    ) {
        super.onPictureInPictureModeChanged(isInPictureInPictureMode, newConfig)
        if (isInPictureInPictureMode) {
            topMenuBar.visibility = View.GONE
            controlBar1.visibility = View.GONE
            controlBar2.visibility = View.GONE
            webView.visibility = View.GONE
            playerView.useController = false
            playerSection.layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
            playerFrame.layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
        } else {
            playerView.useController = true
            if (!isFullscreen) {
                topMenuBar.visibility = View.VISIBLE
                controlBar1.visibility = View.VISIBLE
                controlBar2.visibility = View.VISIBLE
                webView.visibility = View.VISIBLE
                val normalHeight = (resources.displayMetrics.widthPixels.coerceAtMost(
                    resources.displayMetrics.heightPixels
                ) * 9) / 16
                playerSection.layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                )
                playerFrame.layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    normalHeight
                )
            }
        }
    }

    private fun toggleFullscreen(enter: Boolean) {
        isFullscreen = enter
        if (enter) {
            requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
            topMenuBar.visibility = View.GONE
            webView.visibility = View.GONE
            controlBar1.visibility = View.GONE
            controlBar2.visibility = View.GONE
            playerFrame.layoutParams = LinearLayout.LayoutParams(
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
            playerFrame.layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                normalHeight
            )
            topMenuBar.visibility = View.VISIBLE
            controlBar1.visibility = View.VISIBLE
            controlBar2.visibility = View.VISIBLE
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
        stopService(Intent(this, BgPlaybackService::class.java))
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

    override fun onStart() {
        super.onStart()
        stopService(Intent(this, BgPlaybackService::class.java))
    }

    override fun onStop() {
        super.onStop()
        val isPip = Build.VERSION.SDK_INT >= Build.VERSION_CODES.N && isInPictureInPictureMode
        if (isPip) return

        if (bgPlayEnabled && player?.isPlaying == true) {
            updateBgNotificationState()
        } else {
            player?.pause()
            stopService(Intent(this, BgPlaybackService::class.java))
        }
    }

    override fun onDestroy() {
        mainHandler.removeCallbacksAndMessages(null)
        stopService(Intent(this, BgPlaybackService::class.java))
        BgPlaybackService.onTogglePlayPause = null
        BgPlaybackService.onStopPlayback = null
        player?.release()
        player = null
        executor.shutdownNow()
        super.onDestroy()
    }
}

class BgPlaybackService : Service() {

    companion object {
        const val ACTION_UPDATE = "BG_UPDATE"
        const val ACTION_TOGGLE = "BG_TOGGLE"
        const val ACTION_STOP = "BG_STOP"
        const val EXTRA_TITLE = "extra_title"
        const val EXTRA_PLAYING = "extra_playing"
        private const val CHANNEL_ID = "bg_playback_channel"
        private const val NOTIF_ID = 202

        var onTogglePlayPause: (() -> Unit)? = null
        var onStopPlayback: (() -> Unit)? = null
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_TOGGLE -> {
                onTogglePlayPause?.invoke()
                return START_NOT_STICKY
            }
            ACTION_STOP -> {
                onStopPlayback?.invoke()
                stopSelf()
                return START_NOT_STICKY
            }
        }

        val title = intent?.getStringExtra(EXTRA_TITLE) ?: "AdFree Tube"
        val isPlaying = intent?.getBooleanExtra(EXTRA_PLAYING, true) ?: true
        showNotification(title, isPlaying)
        return START_NOT_STICKY
    }

    private fun showNotification(title: String, isPlaying: Boolean) {
        val piFlags = PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE

        val openAppIntent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            piFlags
        )
        val toggleIntent = PendingIntent.getService(
            this,
            1,
            Intent(this, BgPlaybackService::class.java).apply { action = ACTION_TOGGLE },
            piFlags
        )
        val stopIntent = PendingIntent.getService(
            this,
            2,
            Intent(this, BgPlaybackService::class.java).apply { action = ACTION_STOP },
            piFlags
        )

        val builder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Background Audio",
                NotificationManager.IMPORTANCE_LOW
            )
            getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
            Notification.Builder(this, CHANNEL_ID)
        } else {
            @Suppress("DEPRECATION")
            Notification.Builder(this)
        }

        @Suppress("DEPRECATION")
        val notification = builder
            .setContentTitle(title)
            .setContentText(if (isPlaying) "Playing in background" else "Paused")
            .setSmallIcon(android.R.drawable.ic_media_play)
            .setContentIntent(openAppIntent)
            .addAction(
                android.R.drawable.ic_media_pause,
                if (isPlaying) "Pause" else "Play",
                toggleIntent
            )
            .addAction(android.R.drawable.ic_menu_close_clear_cancel, "Stop", stopIntent)
            .setOngoing(isPlaying)
            .build()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NOTIF_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK)
        } else {
            startForeground(NOTIF_ID, notification)
        }
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