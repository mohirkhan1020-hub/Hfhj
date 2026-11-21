package com.wevlo.app

import android.annotation.SuppressLint
import android.app.Activity
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.os.Bundle
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.webkit.CookieManager
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Button
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import java.io.ByteArrayInputStream
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger

class MainActivity : Activity() {

    private lateinit var webView: WebView
    private lateinit var rootContainer: FrameLayout
    private lateinit var urlInput: EditText
    private lateinit var infoBar: TextView
    private var customView: View? = null
    private var customViewCallback: WebChromeClient.CustomViewCallback? = null

    private val blockedCount = AtomicInteger(0)
    private val executor = Executors.newSingleThreadExecutor()

    private val defaultHosts = setOf(
        "doubleclick.net",
        "googlesyndication.com",
        "googleadservices.com",
        "adservice.google.com",
        "pagead2.googlesyndication.com",
        "adnxs.com",
        "adsrvr.org",
        "criteo.com",
        "outbrain.com",
        "taboola.com",
        "mgid.com",
        "popads.net",
        "popcash.net",
        "propellerads.com",
        "an.facebook.com",
        "pixel.facebook.com",
        "unityads.unity3d.com",
        "applovin.com"
    )

    @Volatile
    private var blockedHosts: Set<String> = defaultHosts

    private val adUrlKeywords = listOf(
        "youtube.com/pagead",
        "youtube.com/api/stats/ads",
        "youtube.com/get_midroll",
        "youtube.com/ptracking",
        "/pagead/",
        "/adsense/",
        "googlesyndication.com/pagead"
    )

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        rootContainer = FrameLayout(this)

        val mainLayout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
        }

        val addressBar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setBackgroundColor(Color.parseColor("#202124"))
            setPadding(12, 12, 12, 8)
        }

        val backBtn = Button(this).apply {
            text = "<"
            layoutParams = LinearLayout.LayoutParams(100, ViewGroup.LayoutParams.WRAP_CONTENT)
            setOnClickListener {
                if (webView.canGoBack()) webView.goBack()
            }
        }

        urlInput = EditText(this).apply {
            hint = "Search or enter URL..."
            setTextColor(Color.WHITE)
            setHintTextColor(Color.LTGRAY)
            setBackgroundColor(Color.parseColor("#303134"))
            setPadding(20, 14, 20, 14)
            isSingleLine = true
            imeOptions = EditorInfo.IME_ACTION_GO
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
                setMargins(8, 0, 8, 0)
            }
            setOnEditorActionListener { _, actionId, event ->
                if (actionId == EditorInfo.IME_ACTION_GO ||
                    (event != null && event.keyCode == KeyEvent.KEYCODE_ENTER && event.action == KeyEvent.ACTION_DOWN)
                ) {
                    loadInputUrl()
                    true
                } else {
                    false
                }
            }
        }

        val goBtn = Button(this).apply {
            text = "Go"
            layoutParams = LinearLayout.LayoutParams(120, ViewGroup.LayoutParams.WRAP_CONTENT)
            setOnClickListener { loadInputUrl() }
        }

        addressBar.addView(backBtn)
        addressBar.addView(urlInput)
        addressBar.addView(goBtn)

        val quickBar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setBackgroundColor(Color.parseColor("#292A2D"))
            setPadding(12, 4, 12, 8)
        }

        val googleBtn = Button(this).apply {
            text = "Google"
            textSize = 12f
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            setOnClickListener { webView.loadUrl("https://www.google.com") }
        }

        val ytBtn = Button(this).apply {
            text = "YouTube"
            textSize = 12f
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            setOnClickListener { webView.loadUrl("https://m.youtube.com") }
        }

        val fbBtn = Button(this).apply {
            text = "Facebook"
            textSize = 12f
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            setOnClickListener { webView.loadUrl("https://m.facebook.com") }
        }

        val reloadBtn = Button(this).apply {
            text = "Reload"
            textSize = 12f
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            setOnClickListener { webView.reload() }
        }

        quickBar.addView(googleBtn)
        quickBar.addView(ytBtn)
        quickBar.addView(fbBtn)
        quickBar.addView(reloadBtn)

        infoBar = TextView(this).apply {
            text = "Blocked Ads: 0 | Loading filter list..."
            textSize = 12f
            setTextColor(Color.parseColor("#8AB4F8"))
            setBackgroundColor(Color.parseColor("#171717"))
            setPadding(20, 8, 20, 8)
        }

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
                mediaPlaybackRequiresUserGesture = false
                javaScriptCanOpenWindowsAutomatically = false
                setSupportMultipleWindows(false)
            }

            val cookieManager = CookieManager.getInstance()
            cookieManager.setAcceptCookie(true)
            cookieManager.setAcceptThirdPartyCookies(this, true)

            webViewClient = object : WebViewClient() {
                override fun shouldOverrideUrlLoading(
                    view: WebView?,
                    request: WebResourceRequest?
                ): Boolean {
                    val uri = request?.url ?: return false
                    val scheme = uri.scheme?.lowercase() ?: ""
                    if (scheme != "http" && scheme != "https") {
                        return true
                    }
                    val host = uri.host ?: return false
                    if (isHostBlocked(host)) {
                        incrementBlockedCount()
                        return true
                    }
                    return false
                }

                override fun shouldInterceptRequest(
                    view: WebView?,
                    request: WebResourceRequest?
                ): WebResourceResponse? {
                    val uri = request?.url ?: return null
                    val host = uri.host ?: ""
                    val urlStr = uri.toString().lowercase()

                    if ((host.isNotEmpty() && isHostBlocked(host)) ||
                        adUrlKeywords.any { urlStr.contains(it) }
                    ) {
                        incrementBlockedCount()
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
                    if (!urlInput.hasFocus() && url != null) {
                        urlInput.setText(url)
                    }
                    injectFullAdBlockerJs(view)
                }

                override fun onPageFinished(view: WebView?, url: String?) {
                    super.onPageFinished(view, url)
                    if (!urlInput.hasFocus() && url != null) {
                        urlInput.setText(url)
                    }
                    injectFullAdBlockerJs(view)
                }
            }

            webChromeClient = object : WebChromeClient() {
                override fun onShowCustomView(view: View?, callback: CustomViewCallback?) {
                    if (customView != null) {
                        callback?.onCustomViewHidden()
                        return
                    }
                    customView = view
                    customViewCallback = callback
                    rootContainer.addView(
                        view,
                        FrameLayout.LayoutParams(
                            ViewGroup.LayoutParams.MATCH_PARENT,
                            ViewGroup.LayoutParams.MATCH_PARENT
                        )
                    )
                }

                override fun onHideCustomView() {
                    customView?.let { rootContainer.removeView(it) }
                    customView = null
                    customViewCallback?.onCustomViewHidden()
                    customViewCallback = null
                }
            }
        }

        mainLayout.addView(addressBar)
        mainLayout.addView(quickBar)
        mainLayout.addView(infoBar)
        mainLayout.addView(webView)
        rootContainer.addView(mainLayout)
        setContentView(rootContainer)

        loadFilterListInBackground()
        webView.loadUrl("https://m.youtube.com")
    }

    private fun loadInputUrl() {
        val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
        imm.hideSoftInputFromWindow(urlInput.windowToken, 0)
        urlInput.clearFocus()

        val raw = urlInput.text.toString().trim()
        if (raw.isEmpty()) return

        val finalUrl = when {
            raw.startsWith("http://") || raw.startsWith("https://") -> raw
            raw.contains(".") && !raw.contains(" ") -> "https://$raw"
            else -> "https://www.google.com/search?q=" + URLEncoder.encode(raw, "UTF-8")
        }
        webView.loadUrl(finalUrl)
    }

    private fun isHostBlocked(host: String): Boolean {
        var h = host.lowercase()
        while (true) {
            if (blockedHosts.contains(h)) return true
            val dot = h.indexOf('.')
            if (dot == -1) break
            h = h.substring(dot + 1)
        }
        return false
    }

    private fun incrementBlockedCount() {
        val count = blockedCount.incrementAndGet()
        runOnUiThread {
            infoBar.text = "Blocked Ads: $count | Active Domains: ${blockedHosts.size}"
        }
    }

    private fun loadFilterListInBackground() {
        executor.execute {
            val cacheFile = File(filesDir, "ad_hosts.txt")
            try {
                if (cacheFile.exists() && cacheFile.length() > 10000) {
                    val cached = parseHostsFile(cacheFile)
                    if (cached.size > 1000) {
                        blockedHosts = cached
                        updateInfoBar()
                    }
                }

                val threeDaysMs = 3L * 24 * 60 * 60 * 1000
                val needsDownload = !cacheFile.exists() ||
                    (System.currentTimeMillis() - cacheFile.lastModified() > threeDaysMs)

                if (needsDownload) {
                    val url = URL("https://raw.githubusercontent.com/StevenBlack/hosts/master/hosts")
                    val conn = (url.openConnection() as HttpURLConnection).apply {
                        connectTimeout = 10000
                        readTimeout = 15000
                    }
                    if (conn.responseCode == 200) {
                        conn.inputStream.use { input ->
                            cacheFile.outputStream().use { output ->
                                input.copyTo(output)
                            }
                        }
                        val fresh = parseHostsFile(cacheFile)
                        if (fresh.size > 1000) {
                            blockedHosts = fresh
                        }
                    }
                    conn.disconnect()
                }
            } catch (_: Exception) {
            }
            updateInfoBar()
        }
    }

    private fun parseHostsFile(file: File): Set<String> {
        val set = HashSet<String>(defaultHosts)
        file.bufferedReader().useLines { lines ->
            for (line in lines) {
                if (line.startsWith("0.0.0.0 ")) {
                    val domain = line.substring(8).substringBefore('#').trim().lowercase()
                    if (domain.isNotEmpty() && domain != "0.0.0.0") {
                        set.add(domain)
                    }
                }
            }
        }
        return set
    }

    private fun updateInfoBar() {
        runOnUiThread {
            infoBar.text = "Blocked Ads: ${blockedCount.get()} | Active Domains: ${blockedHosts.size}"
        }
    }

    private fun injectFullAdBlockerJs(view: WebView?) {
        val js = """
            (function() {
                if (window.__universalAdBlock) return;
                window.__universalAdBlock = true;

                function stripAds(obj) {
                    if (!obj || typeof obj !== 'object') return obj;
                    if ('adPlacements' in obj) obj.adPlacements = [];
                    if ('playerAds' in obj) obj.playerAds = [];
                    if ('adSlots' in obj) obj.adSlots = [];
                    if (obj.playerResponse && typeof obj.playerResponse === 'object') {
                        stripAds(obj.playerResponse);
                    }
                    return obj;
                }

                if (window.ytInitialPlayerResponse) {
                    stripAds(window.ytInitialPlayerResponse);
                }

                var origParse = JSON.parse;
                JSON.parse = function(text, reviver) {
                    var data = origParse.call(this, text, reviver);
                    try {
                        if (location.hostname.indexOf('youtube.com') !== -1) {
                            return stripAds(data);
                        }
                    } catch (e) {}
                    return data;
                };

                if (window.Response && Response.prototype.json) {
                    var origJson = Response.prototype.json;
                    Response.prototype.json = function() {
                        return origJson.call(this).then(function(data) {
                            try {
                                if (location.hostname.indexOf('youtube.com') !== -1) {
                                    return stripAds(data);
                                }
                            } catch (e) {}
                            return data;
                        });
                    };
                }

                function addCss() {
                    if (!document.head || document.getElementById('__u_ad_css')) return;
                    var s = document.createElement('style');
                    s.id = '__u_ad_css';
                    s.innerHTML = `
                        ins.adsbygoogle,
                        .adsbygoogle,
                        [id^="google_ads_"],
                        [id^="div-gpt-ad"],
                        iframe[src*="doubleclick.net"],
                        iframe[src*="googlesyndication.com"],
                        ytm-ad-slot-renderer,
                        ytm-in-feed-ad-layout-renderer,
                        ytm-promoted-sparkles-web-renderer,
                        ytm-promoted-sparkles-text-search-renderer,
                        ytm-promoted-video-renderer,
                        ytm-companion-slot-renderer,
                        ad-slot-renderer,
                        .ytp-ad-overlay-container,
                        .ytp-ad-message-container {
                            display: none !important;
                        }
                    `;
                    document.head.appendChild(s);
                }

                setInterval(function() {
                    addCss();

                    if (location.hostname.indexOf('youtube.com') !== -1) {
                        if (window.ytInitialPlayerResponse) {
                            stripAds(window.ytInitialPlayerResponse);
                        }

                        var player = document.querySelector('#movie_player, .html5-video-player');
                        var adOverlay = document.querySelector('.ytp-ad-player-overlay-layout, .ytp-ad-text, .ytp-ad-preview-container');
                        var isAd = (player && (player.classList.contains('ad-showing') || player.classList.contains('ad-interrupting'))) || !!adOverlay;
                        var videos = document.querySelectorAll('video');

                        for (var i = 0; i < videos.length; i++) {
                            var v = videos[i];
                            if (isAd) {
                                if (!window.__ytAdMuted) {
                                    window.__prevMuted = v.muted;
                                    window.__ytAdMuted = true;
                                }
                                v.muted = true;
                                try { v.playbackRate = 16.0; } catch (e) {}
                                if (isFinite(v.duration) && v.duration > 0.5) {
                                    try { v.currentTime = v.duration - 0.1; } catch (e) {}
                                }
                            } else if (window.__ytAdMuted) {
                                v.muted = !!window.__prevMuted;
                                try { v.playbackRate = 1.0; } catch (e) {}
                                window.__ytAdMuted = false;
                            }
                        }

                        if (isAd) {
                            var skipBtns = document.querySelectorAll(
                                '.ytp-ad-skip-button, .ytp-skip-ad-button, .ytp-ad-skip-button-modern, [class*="ytp-skip-ad"], button[aria-label*="Skip"]'
                            );
                            for (var j = 0; j < skipBtns.length; j++) {
                                skipBtns[j].click();
                            }
                        }
                    }

                    if (location.hostname.indexOf('facebook.com') !== -1) {
                        var spans = document.querySelectorAll('span, a');
                        for (var k = 0; k < spans.length; k++) {
                            if (spans[k].textContent && spans[k].textContent.trim() === 'Sponsored') {
                                var post = spans[k].closest('article, [data-tracking-duration-id]');
                                if (post) post.style.display = 'none';
                            }
                        }
                    }
                }, 300);
            })();
        """.trimIndent()
        view?.evaluateJavascript(js, null)
    }

    @Suppress("DEPRECATION")
    override fun onBackPressed() {
        if (customView != null) {
            webView.webChromeClient?.onHideCustomView()
        } else if (webView.canGoBack()) {
            webView.goBack()
        } else {
            super.onBackPressed()
        }
    }

    override fun onDestroy() {
        executor.shutdownNow()
        super.onDestroy()
    }
}