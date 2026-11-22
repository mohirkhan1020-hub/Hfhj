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
import android.graphics.drawable.GradientDrawable
import android.media.AudioManager
import android.media.session.MediaSession
import android.media.session.PlaybackState
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
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
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
    private lateinit var shortsToggleBtn: TextView
    private lateinit var playerSection: LinearLayout
    private lateinit var playerFrame: FrameLayout
    private lateinit var playerView: PlayerView
    private lateinit var loadingSpinner: ProgressBar
    private lateinit var gestureBadge: TextView
    private lateinit var unlockBtn: TextView
    private lateinit var controlBar1: LinearLayout
    private lateinit var actionScrollBar: HorizontalScrollView
    private lateinit var statusText: TextView
    private lateinit var channelText: TextView
    private lateinit var statsText: TextView
    private lateinit var qualityBtn: TextView
    private lateinit var speedBtn: TextView
    private lateinit var loopBtn: TextView
    private lateinit var zoomBtn: TextView
    private lateinit var bgPlayBtn: TextView
    private lateinit var lockBtn: TextView
    private lateinit var webView: WebView

    private val executor = Executors.newFixedThreadPool(3)
    private val mainHandler = Handler(Looper.getMainLooper())

    private var availableQualities: List<VideoQualityOption> = emptyList()
    private var currentQualityLabel = "Auto"
    private var currentSpeed = 1.0f
    private var isFullscreen = false
    private var isLooping = false
    private var isZoomed = false
    private var isTouchLocked = false
    private var bgPlayEnabled = true
    private var blockShortsEnabled = false

    private var currentVideoTitle: String = "WevloTube"
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
            val p = BgPlaybackService.player
            if (p != null && p.isPlaying) {
                val pos = p.currentPosition
                for (seg in sponsorSegments) {
                    if (pos in seg.startMs until (seg.endMs - 400) && lastSkippedEndMs != seg.endMs) {
                        lastSkippedEndMs = seg.endMs
                        p.seekTo(seg.endMs)
                        showGestureBadge("⏭ Skipped ${seg.category}")
                        break
                    }
                }
            }
            mainHandler.postDelayed(this, 500)
        }
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    private fun roundedBg(fillHex: String, radiusDp: Int, strokeHex: String? = null): GradientDrawable {
        return GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = dp(radiusDp).toFloat()
            setColor(Color.parseColor(fillHex))
            if (strokeHex != null) {
                setStroke(dp(1), Color.parseColor(strokeHex))
            }
        }
    }

    private fun makePill(
        label: String,
        bgHex: String = "#23232E",
        textHex: String = "#FFFFFF",
        strokeHex: String = "#353545",
        onClick: () -> Unit
    ): TextView {
        return TextView(this).apply {
            text = label
            setTextColor(Color.parseColor(textHex))
            textSize = 12f
            setTypeface(Typeface.SANS_SERIF, Typeface.BOLD)
            gravity = Gravity.CENTER
            background = roundedBg(bgHex, 20, strokeHex)
            setPadding(dp(14), dp(8), dp(14), dp(8))
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply {
                setMargins(0, 0, dp(8), 0)
            }
            setOnClickListener { onClick() }
        }
    }

    @SuppressLint("SetJavaScriptEnabled", "ClickableViewAccessibility")
    @OptIn(UnstableApi::class)
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        window.statusBarColor = Color.parseColor("#0B0B0F")
        window.navigationBarColor = Color.parseColor("#0B0B0F")

        NewPipe.init(OkHttpDownloader.getInstance())
        BgPlaybackService.ensurePlayer(applicationContext)

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
            setBackgroundColor(Color.parseColor("#0B0B0F"))
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
        }

        // ১. আধুনিক ব্র্যান্ড হেডার বার
        topMenuBar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setBackgroundColor(Color.parseColor("#121218"))
            setPadding(dp(12), dp(10), dp(12), dp(10))
        }

        val logoBadge = TextView(this).apply {
            text = "▶"
            setTextColor(Color.WHITE)
            textSize = 13f
            gravity = Gravity.CENTER
            background = roundedBg("#FF2E4C", 8)
            setPadding(dp(9), dp(4), dp(9), dp(4))
        }

        val brandTitle = TextView(this).apply {
            text = "WevloTube"
            setTextColor(Color.WHITE)
            textSize = 16f
            setTypeface(Typeface.SANS_SERIF, Typeface.BOLD)
            setPadding(dp(8), 0, dp(6), 0)
        }

        val adFreeTag = TextView(this).apply {
            text = "NO ADS"
            setTextColor(Color.parseColor("#4ADE80"))
            textSize = 9f
            setTypeface(Typeface.SANS_SERIF, Typeface.BOLD)
            background = roundedBg("#162E21", 10, "#22543D")
            setPadding(dp(6), dp(2), dp(6), dp(2))
        }

        val spacer = View(this).apply {
            layoutParams = LinearLayout.LayoutParams(0, 1, 1f)
        }

        val homeBtn = makePill("🏠 Home", "#1E1E28", "#E4E4EB", "#2F2F3D") {
            webView.loadUrl("https://m.youtube.com")
        }

        val historyBtn = makePill("🕒", "#1E1E28", "#E4E4EB", "#2F2F3D") {
            showHistoryDialog()
        }

        shortsToggleBtn = makePill("Shorts: ON", "#1E1E28", "#E4E4EB", "#2F2F3D") {
            blockShortsEnabled = !blockShortsEnabled
            shortsToggleBtn.text = if (blockShortsEnabled) "Shorts: OFF" else "Shorts: ON"
            shortsToggleBtn.background = roundedBg(
                if (blockShortsEnabled) "#3B1822" else "#1E1E28",
                20,
                if (blockShortsEnabled) "#FF2E4C" else "#2F2F3D"
            )
            injectYouTubeCss(webView)
        }.apply {
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
        }

        topMenuBar.addView(logoBadge)
        topMenuBar.addView(brandTitle)
        topMenuBar.addView(adFreeTag)
        topMenuBar.addView(spacer)
        topMenuBar.addView(homeBtn)
        topMenuBar.addView(historyBtn)
        topMenuBar.addView(shortsToggleBtn)

        // ২. অ্যাডভান্সড ExoPlayer সেকশন
        playerSection = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.parseColor("#121218"))
            visibility = if (BgPlaybackService.player?.currentMediaItem != null) View.VISIBLE else View.GONE
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
        }

        val playerHeight = (resources.displayMetrics.widthPixels * 9) / 16
        playerFrame = FrameLayout(this).apply {
            setBackgroundColor(Color.BLACK)
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
            controllerShowTimeoutMs = 2500
            setKeepContentOnPlayerReset(true)
            setShowBuffering(PlayerView.SHOW_BUFFERING_WHEN_PLAYING)
            setShowNextButton(false)
            setShowPreviousButton(false)
            setFullscreenButtonClickListener { shouldEnter ->
                toggleFullscreen(shouldEnter)
            }
        }

        loadingSpinner = ProgressBar(this).apply {
            visibility = View.GONE
            layoutParams = FrameLayout.LayoutParams(dp(42), dp(42), Gravity.CENTER)
        }

        gestureBadge = TextView(this).apply {
            visibility = View.GONE
            setTextColor(Color.WHITE)
            background = roundedBg("#CC14141E", 20, "#44FFFFFF")
            textSize = 14f
            setTypeface(Typeface.SANS_SERIF, Typeface.BOLD)
            setPadding(dp(18), dp(10), dp(18), dp(10))
            layoutParams = FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.CENTER
            )
        }

        unlockBtn = TextView(this).apply {
            visibility = View.GONE
            text = "🔓 Unlock"
            setTextColor(Color.WHITE)
            textSize = 12f
            setTypeface(Typeface.SANS_SERIF, Typeface.BOLD)
            background = roundedBg("#CCFF2E4C", 20)
            setPadding(dp(14), dp(8), dp(14), dp(8))
            layoutParams = FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.TOP or Gravity.END
            ).apply {
                setMargins(0, dp(14), dp(14), 0)
            }
            setOnClickListener { setPlayerTouchLocked(false) }
        }

        playerFrame.addView(playerView)
        playerFrame.addView(loadingSpinner)
        playerFrame.addView(gestureBadge)
        playerFrame.addView(unlockBtn)
        playerView.player = BgPlaybackService.player

        setupPlayerGestures()
        setupBackgroundCallbacks()

        // ৩. ভিডিও টাইটেল ও মেটাডেটা কার্ড
        controlBar1 = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.parseColor("#16161F"))
            setPadding(dp(14), dp(10), dp(14), dp(8))
        }

        statusText = TextView(this).apply {
            text = BgPlaybackService.currentTitle
            setTextColor(Color.WHITE)
            textSize = 15f
            maxLines = 2
            setTypeface(Typeface.SANS_SERIF, Typeface.BOLD)
        }

        channelText = TextView(this).apply {
            text = ""
            setTextColor(Color.parseColor("#A1A1B5"))
            textSize = 12f
            setPadding(0, dp(3), 0, dp(2))
        }

        statsText = TextView(this).apply {
            text = "🛡 Ad-Free  •  SponsorBlock Active"
            setTextColor(Color.parseColor("#4ADE80"))
            textSize = 11.5f
            setPadding(0, dp(2), 0, dp(2))
        }

        controlBar1.addView(statusText)
        controlBar1.addView(channelText)
        controlBar1.addView(statsText)

        // ৪. স্ক্রলযোগ্য আধুনিক প্লেয়ার অ্যাকশন পিল বার
        actionScrollBar = HorizontalScrollView(this).apply {
            isHorizontalScrollBarEnabled = false
            setBackgroundColor(Color.parseColor("#121218"))
            setPadding(dp(12), dp(6), dp(12), dp(10))
        }

        val pillRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }

        qualityBtn = makePill("⚡ Quality", "#242432", "#FFFFFF", "#FF2E4C") { showQualityDialog() }
        speedBtn = makePill("⏩ 1.0x") { showSpeedDialog() }
        loopBtn = makePill("🔁 Loop: OFF") { toggleLoopMode() }
        zoomBtn = makePill("📐 Fit") { toggleZoomMode() }
        val pipBtn = makePill("🖼 PiP") { enterPipMode() }
        bgPlayBtn = makePill("🎧 BG: ON", "#182D24", "#4ADE80", "#22543D") {
            bgPlayEnabled = !bgPlayEnabled
            bgPlayBtn.text = if (bgPlayEnabled) "🎧 BG: ON" else "🎧 BG: OFF"
            bgPlayBtn.background = roundedBg(
                if (bgPlayEnabled) "#182D24" else "#23232E",
                20,
                if (bgPlayEnabled) "#22543D" else "#353545"
            )
            bgPlayBtn.setTextColor(Color.parseColor(if (bgPlayEnabled) "#4ADE80" else "#FFFFFF"))
            if (!bgPlayEnabled) {
                stopService(Intent(this, BgPlaybackService::class.java))
            } else if (BgPlaybackService.player?.isPlaying == true) {
                startBgPlaybackService()
            }
        }
        lockBtn = makePill("🔒 Lock") { setPlayerTouchLocked(true) }
        val saveBtn = makePill("⬇ Save", "#1D263B", "#93C5FD", "#2E4068") { showDownloadDialog() }
        val closeBtn = makePill("✕ Close", "#321A22", "#FCA5A5", "#5C2636") { closePlayer() }.apply {
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
        }

        pillRow.addView(qualityBtn)
        pillRow.addView(speedBtn)
        pillRow.addView(loopBtn)
        pillRow.addView(zoomBtn)
        pillRow.addView(pipBtn)
        pillRow.addView(bgPlayBtn)
        pillRow.addView(lockBtn)
        pillRow.addView(saveBtn)
        pillRow.addView(closeBtn)
        actionScrollBar.addView(pillRow)

        playerSection.addView(playerFrame)
        playerSection.addView(controlBar1)
        playerSection.addView(actionScrollBar)

        // ৫. আসল YouTube ফিড (WebView)
        webView = WebView(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                0,
                1f
            )
            setBackgroundColor(Color.parseColor("#0B0B0F"))
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

    private fun setPlayerTouchLocked(locked: Boolean) {
        isTouchLocked = locked
        playerView.useController = !locked
        if (locked) {
            playerView.hideController()
            unlockBtn.visibility = View.VISIBLE
            showGestureBadge("🔒 Touch Locked")
        } else {
            unlockBtn.visibility = View.GONE
            playerView.showController()
            showGestureBadge("🔓 Unlocked")
        }
    }

    private fun toggleLoopMode() {
        isLooping = !isLooping
        BgPlaybackService.player?.repeatMode = if (isLooping) {
            Player.REPEAT_MODE_ONE
        } else {
            Player.REPEAT_MODE_OFF
        }
        loopBtn.text = if (isLooping) "🔁 Loop: ON" else "🔁 Loop: OFF"
        loopBtn.background = roundedBg(
            if (isLooping) "#182D24" else "#23232E",
            20,
            if (isLooping) "#22543D" else "#353545"
        )
    }

    @OptIn(UnstableApi::class)
    private fun toggleZoomMode() {
        isZoomed = !isZoomed
        playerView.resizeMode = if (isZoomed) {
            AspectRatioFrameLayout.RESIZE_MODE_ZOOM
        } else {
            AspectRatioFrameLayout.RESIZE_MODE_FIT
        }
        zoomBtn.text = if (isZoomed) "📐 Zoom" else "📐 Fit"
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun setupPlayerGestures() {
        val gestureDetector = GestureDetector(this, object : GestureDetector.SimpleOnGestureListener() {
            override fun onDoubleTap(e: MotionEvent): Boolean {
                val p = BgPlaybackService.player ?: return false
                if (e.x < playerView.width / 2f) {
                    p.seekBack()
                    showGestureBadge("⏪ -10s")
                } else {
                    p.seekForward()
                    showGestureBadge("⏩ +10s")
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
                    showGestureBadge("☀ Brightness ${(brightnessRatio * 100).toInt()}%")
                } else {
                    val audioManager = getSystemService(Context.AUDIO_SERVICE) as AudioManager
                    val maxVol = audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC).coerceAtLeast(1)
                    volumeRatio = (volumeRatio + delta).coerceIn(0.0f, 1.0f)
                    val newVol = (volumeRatio * maxVol).toInt()
                    audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, newVol, 0)
                    showGestureBadge("🔊 Volume ${(volumeRatio * 100).toInt()}%")
                }
                return true
            }
        })

        playerView.setOnTouchListener { _, event ->
            if (isTouchLocked) {
                if (event.action == MotionEvent.ACTION_UP) {
                    unlockBtn.visibility = View.VISIBLE
                    showGestureBadge("🔒 Locked — Tap Unlock")
                }
                return@setOnTouchListener true
            }
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
        BgPlaybackService.onCloseFromNotification = {
            mainHandler.post {
                playerSection.visibility = View.GONE
            }
        }
    }

    private fun startBgPlaybackService() {
        val intent = Intent(this, BgPlaybackService::class.java).apply {
            action = BgPlaybackService.ACTION_START_OR_UPDATE
            putExtra(BgPlaybackService.EXTRA_TITLE, currentVideoTitle)
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
        sponsorSegments = emptyList()
        lastSkippedEndMs = -1L
        bestMuxedDownloadUrl = null
        bestAudioDownloadUrl = null

        playerSection.visibility = View.VISIBLE
        loadingSpinner.visibility = View.VISIBLE
        statusText.text = "Loading video..."
        channelText.text = ""
        statsText.text = "Fetching ad-free stream & SponsorBlock..."
        qualityBtn.text = "⚡ Quality"

        val watchUrl = "https://www.youtube.com/watch?v=$videoId"

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
                    loadingSpinner.visibility = View.GONE
                    availableQualities = options
                    if (defaultOption != null) {
                        currentVideoTitle = info.name
                        BgPlaybackService.currentTitle = info.name
                        statusText.text = info.name
                        val views = if (info.viewCount > 0) "${formatCount(info.viewCount)} views" else ""
                        channelText.text = listOfNotNull(
                            info.uploaderName?.takeIf { it.isNotBlank() },
                            views.takeIf { it.isNotBlank() }
                        ).joinToString("  •  ")
                        saveToHistory(videoId, info.name)
                        startOptionInPlayer(defaultOption, resumePositionMs = 0L)
                    } else {
                        statusText.text = "ভিডিও স্ট্রিম পাওয়া যায়নি"
                    }
                }
            } catch (e: Exception) {
                runOnUiThread {
                    loadingSpinner.visibility = View.GONE
                    statusText.text = "Error: ${e.localizedMessage}"
                }
            }
        }

        executor.execute {
            val segs = fetchSponsorSegments(videoId)
            sponsorSegments = segs
            val votesText = fetchDislikeStats(videoId)
            runOnUiThread {
                val sbInfo = if (segs.isNotEmpty()) "🛡 SponsorBlock: ${segs.size} skips" else "🛡 0% Ads"
                statsText.text = if (votesText != null) "$votesText   •   $sbInfo" else sbInfo
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
        qualityBtn.text = "⚡ ${option.label}"

        val p = BgPlaybackService.ensurePlayer(applicationContext)
        playerView.player = p

        p.trackSelectionParameters = p.trackSelectionParameters.buildUpon()
            .setTrackTypeDisabled(C.TRACK_TYPE_VIDEO, false)
            .build()
        p.repeatMode = if (isLooping) Player.REPEAT_MODE_ONE else Player.REPEAT_MODE_OFF
        p.setMediaSource(mediaSource)
        p.prepare()
        if (resumePositionMs > 0L) p.seekTo(resumePositionMs)
        p.setPlaybackSpeed(currentSpeed)
        p.playWhenReady = true

        if (bgPlayEnabled) {
            startBgPlaybackService()
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
                val pos = BgPlaybackService.player?.currentPosition ?: 0L
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
                speedBtn.text = "⏩ ${currentSpeed}x"
                BgPlaybackService.player?.setPlaybackSpeed(currentSpeed)
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
            actionScrollBar.visibility = View.GONE
            webView.visibility = View.GONE
            unlockBtn.visibility = View.GONE
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
            playerView.useController = !isTouchLocked
            if (!isFullscreen) {
                topMenuBar.visibility = View.VISIBLE
                controlBar1.visibility = View.VISIBLE
                actionScrollBar.visibility = View.VISIBLE
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
            actionScrollBar.visibility = View.GONE
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
            if (isTouchLocked) setPlayerTouchLocked(false)
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
            actionScrollBar.visibility = View.VISIBLE
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
        if (isTouchLocked) setPlayerTouchLocked(false)
        if (isFullscreen) toggleFullscreen(false)
        BgPlaybackService.player?.stop()
        stopService(Intent(this, BgPlaybackService::class.java))
        playerSection.visibility = View.GONE
    }

    @Suppress("DEPRECATION")
    override fun onBackPressed() {
        if (isTouchLocked) {
            showGestureBadge("🔒 Locked — Tap Unlock")
        } else if (isFullscreen) {
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
        val p = BgPlaybackService.player
        if (p != null) {
            p.trackSelectionParameters = p.trackSelectionParameters.buildUpon()
                .setTrackTypeDisabled(C.TRACK_TYPE_VIDEO, false)
                .build()
            playerView.player = p
            if (p.currentMediaItem != null) {
                playerSection.visibility = View.VISIBLE
                statusText.text = BgPlaybackService.currentTitle
            }
        }
    }

    override fun onStop() {
        super.onStop()
        val isPip = Build.VERSION.SDK_INT >= Build.VERSION_CODES.N && isInPictureInPictureMode
        if (isPip) return

        val p = BgPlaybackService.player
        if (bgPlayEnabled && p != null && p.isPlaying) {
            playerView.player = null
            p.trackSelectionParameters = p.trackSelectionParameters.buildUpon()
                .setTrackTypeDisabled(C.TRACK_TYPE_VIDEO, true)
                .build()
        } else {
            p?.pause()
            stopService(Intent(this, BgPlaybackService::class.java))
        }
    }

    override fun onDestroy() {
        mainHandler.removeCallbacksAndMessages(null)
        playerView.player = null
        BgPlaybackService.onCloseFromNotification = null
        if (!bgPlayEnabled || BgPlaybackService.player?.isPlaying != true) {
            stopService(Intent(this, BgPlaybackService::class.java))
        }
        executor.shutdownNow()
        super.onDestroy()
    }
}

class BgPlaybackService : Service() {

    private var mediaSession: MediaSession? = null

    companion object {
        const val ACTION_START_OR_UPDATE = "BG_START"
        const val ACTION_TOGGLE = "BG_TOGGLE"
        const val ACTION_STOP = "BG_STOP"
        const val EXTRA_TITLE = "extra_title"
        private const val CHANNEL_ID = "bg_playback_channel"
        private const val NOTIF_ID = 202

        var currentTitle: String = "WevloTube"
        var player: ExoPlayer? = null
        var onCloseFromNotification: (() -> Unit)? = null

        @OptIn(UnstableApi::class)
        fun ensurePlayer(context: Context): ExoPlayer {
            val existing = player
            if (existing != null) return existing

            val audioAttrs = AudioAttributes.Builder()
                .setContentType(C.AUDIO_CONTENT_TYPE_MOVIE)
                .setUsage(C.USAGE_MEDIA)
                .build()

            val created = ExoPlayer.Builder(context.applicationContext)
                .setSeekBackIncrementMs(10_000L)
                .setSeekForwardIncrementMs(10_000L)
                .setWakeMode(C.WAKE_MODE_NETWORK)
                .setHandleAudioBecomingNoisy(true)
                .setAudioAttributes(audioAttrs, true)
                .build()

            player = created
            return created
        }
    }

    override fun onCreate() {
        super.onCreate()
        ensurePlayer(applicationContext)

        val session = MediaSession(this, "WevloTubeMediaSession").apply {
            setCallback(object : MediaSession.Callback() {
                override fun onPlay() {
                    player?.play()
                    refreshNotification()
                }

                override fun onPause() {
                    player?.pause()
                    refreshNotification()
                }

                override fun onStop() {
                    stopPlaybackAndService()
                }
            })
            isActive = true
        }
        mediaSession = session

        player?.addListener(object : Player.Listener {
            override fun onIsPlayingChanged(isPlaying: Boolean) {
                refreshNotification()
            }
        })
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_TOGGLE -> {
                val p = player
                if (p != null) {
                    if (p.isPlaying) p.pause() else p.play()
                }
                refreshNotification()
                return START_STICKY
            }
            ACTION_STOP -> {
                stopPlaybackAndService()
                return START_NOT_STICKY
            }
        }

        val title = intent?.getStringExtra(EXTRA_TITLE)
        if (!title.isNullOrBlank()) currentTitle = title
        refreshNotification()
        return START_STICKY
    }

    private fun stopPlaybackAndService() {
        player?.stop()
        onCloseFromNotification?.invoke()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            stopForeground(STOP_FOREGROUND_REMOVE)
        } else {
            @Suppress("DEPRECATION")
            stopForeground(true)
        }
        stopSelf()
    }

    private fun refreshNotification() {
        val p = player
        val isPlaying = p?.isPlaying == true
        val pos = p?.currentPosition ?: 0L

        val state = PlaybackState.Builder()
            .setActions(
                PlaybackState.ACTION_PLAY or
                    PlaybackState.ACTION_PAUSE or
                    PlaybackState.ACTION_PLAY_PAUSE or
                    PlaybackState.ACTION_STOP
            )
            .setState(
                if (isPlaying) PlaybackState.STATE_PLAYING else PlaybackState.STATE_PAUSED,
                pos,
                1.0f
            )
            .build()
        mediaSession?.setPlaybackState(state)

        val piFlags = PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE

        val openAppIntent = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java), piFlags
        )
        val toggleIntent = PendingIntent.getService(
            this, 1, Intent(this, BgPlaybackService::class.java).apply { action = ACTION_TOGGLE }, piFlags
        )
        val stopIntent = PendingIntent.getService(
            this, 2, Intent(this, BgPlaybackService::class.java).apply { action = ACTION_STOP }, piFlags
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

        val style = Notification.MediaStyle()
            .setMediaSession(mediaSession?.sessionToken)
            .setShowActionsInCompactView(0, 1)

        @Suppress("DEPRECATION")
        val notification = builder
            .setContentTitle(currentTitle)
            .setContentText(if (isPlaying) "Playing in background" else "Paused")
            .setSmallIcon(android.R.drawable.ic_media_play)
            .setContentIntent(openAppIntent)
            .setStyle(style)
            .addAction(
                if (isPlaying) android.R.drawable.ic_media_pause else android.R.drawable.ic_media_play,
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

    override fun onDestroy() {
        mediaSession?.isActive = false
        mediaSession?.release()
        mediaSession = null
        player?.release()
        player = null
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