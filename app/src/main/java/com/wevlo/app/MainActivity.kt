package com.wevlo.app

import android.annotation.SuppressLint
import android.app.Activity
import android.graphics.Bitmap
import android.graphics.Color
import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import android.webkit.CookieManager
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Button
import android.widget.FrameLayout
import android.widget.LinearLayout
import java.io.ByteArrayInputStream

class MainActivity : Activity() {

    private lateinit var webView: WebView
    private lateinit var rootContainer: FrameLayout
    private var customView: View? = null
    private var customViewCallback: WebChromeClient.CustomViewCallback? = null

    private val adUrlKeywords = listOf(
        "doubleclick.net",
        "googlesyndication.com",
        "googleadservices.com",
        "adservice.google.com",
        "youtube.com/pagead",
        "youtube.com/api/stats/ads",
        "youtube.com/get_midroll",
        "youtube.com/ptracking",
        "an.facebook.com",
        "pixel.facebook.com"
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

        val topBar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setBackgroundColor(Color.parseColor("#202124"))
            setPadding(16, 16, 16, 16)
        }

        val ytBtn = Button(this).apply {
            text = "YouTube"
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            setOnClickListener { webView.loadUrl("https://m.youtube.com") }
        }

        val fbBtn = Button(this).apply {
            text = "Facebook"
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            setOnClickListener { webView.loadUrl("https://m.facebook.com") }
        }

        val backBtn = Button(this).apply {
            text = "Back"
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 0.7f)
            setOnClickListener {
                if (webView.canGoBack()) webView.goBack()
            }
        }

        topBar.addView(ytBtn)
        topBar.addView(fbBtn)
        topBar.addView(backBtn)

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
            }

            val cookieManager = CookieManager.getInstance()
            cookieManager.setAcceptCookie(true)
            cookieManager.setAcceptThirdPartyCookies(this, true)

            webViewClient = object : WebViewClient() {
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
                    injectAdBlockerJs(view)
                }

                override fun onPageFinished(view: WebView?, url: String?) {
                    super.onPageFinished(view, url)
                    injectAdBlockerJs(view)
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

        mainLayout.addView(topBar)
        mainLayout.addView(webView)
        rootContainer.addView(mainLayout)
        setContentView(rootContainer)

        webView.loadUrl("https://m.youtube.com")
    }

    private fun injectAdBlockerJs(view: WebView?) {
        val js = """
            (function() {
                if (window.__adBlockInjected) return;
                window.__adBlockInjected = true;

                // ১. প্লেয়ারের JSON ডেটা থেকেই অ্যাড রিমুভ করা
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

                var origParse = JSON.parse;
                JSON.parse = function(text, reviver) {
                    var data = origParse.call(this, text, reviver);
                    return stripAds(data);
                };

                if (window.Response && Response.prototype.json) {
                    var origJson = Response.prototype.json;
                    Response.prototype.json = function() {
                        return origJson.call(this).then(function(data) {
                            return stripAds(data);
                        });
                    };
                }

                // ২. মোবাইল YouTube-এর ফিড/ব্যানার অ্যাড লুকানোর CSS
                function addHideCss() {
                    if (!document.head || document.getElementById('__yt_ad_hide_css')) return;
                    var style = document.createElement('style');
                    style.id = '__yt_ad_hide_css';
                    style.innerHTML = `
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
                    document.head.appendChild(style);
                }

                // ৩. কোনো ভিডিও অ্যাড তবুও ঢুকে পড়লে সাথে সাথে স্কিপ ও মিউট করা
                setInterval(function() {
                    addHideCss();

                    var player = document.querySelector('#movie_player, .html5-video-player');
                    var adOverlay = document.querySelector('.ytp-ad-player-overlay-layout, .ytp-ad-text');
                    var video = document.querySelector('video');

                    var isAd = false;
                    if (player && (player.classList.contains('ad-showing') || player.classList.contains('ad-interrupting'))) {
                        isAd = true;
                    } else if (adOverlay) {
                        isAd = true;
                    }

                    if (video && isAd) {
                        if (!window.__ytAdMuted) {
                            window.__prevMuted = video.muted;
                            window.__ytAdMuted = true;
                        }
                        video.muted = true;
                        video.playbackRate = 16.0;
                        if (isFinite(video.duration) && video.duration > 0.5) {
                            video.currentTime = video.duration - 0.1;
                        }
                        var skipBtns = document.querySelectorAll(
                            '.ytp-ad-skip-button, .ytp-skip-ad-button, .ytp-ad-skip-button-modern, button[aria-label*="Skip"], button[class*="skip-ad"]'
                        );
                        for (var i = 0; i < skipBtns.length; i++) {
                            skipBtns[i].click();
                        }
                    } else if (video && window.__ytAdMuted) {
                        video.muted = !!window.__prevMuted;
                        video.playbackRate = 1.0;
                        window.__ytAdMuted = false;
                    }

                    if (location.hostname.indexOf('facebook.com') !== -1) {
                        var spans = document.querySelectorAll('span, a');
                        for (var j = 0; j < spans.length; j++) {
                            if (spans[j].textContent && spans[j].textContent.trim() === 'Sponsored') {
                                var post = spans[j].closest('article, [data-tracking-duration-id]');
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
}