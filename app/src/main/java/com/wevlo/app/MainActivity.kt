package com.wevlo.app

import android.annotation.SuppressLint
import android.app.Activity
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

                var style = document.createElement('style');
                style.innerHTML = `
                    ytm-promoted-sparkles-web-renderer,
                    ytm-promoted-video-renderer,
                    ytm-companion-slot-renderer,
                    ad-slot-renderer,
                    .ytp-ad-overlay-container,
                    .ytp-ad-message-container {
                        display: none !important;
                    }
                `;
                document.head.appendChild(style);

                setInterval(function() {
                    var player = document.querySelector('#movie_player, .html5-video-player');
                    var video = document.querySelector('video');
                    if (player && video) {
                        var isAd = player.classList.contains('ad-showing') ||
                                   player.classList.contains('ad-interrupting');
                        if (isAd) {
                            if (!window.__ytAdMuted) {
                                window.__prevMuted = video.muted;
                                window.__ytAdMuted = true;
                            }
                            video.muted = true;
                            video.playbackRate = 16.0;
                            if (isFinite(video.duration) && video.duration > 0) {
                                video.currentTime = video.duration;
                            }
                            var skipBtn = document.querySelector(
                                '.ytp-ad-skip-button, .ytp-skip-ad-button, .ytp-ad-skip-button-modern'
                            );
                            if (skipBtn) skipBtn.click();
                        } else if (window.__ytAdMuted) {
                            video.muted = !!window.__prevMuted;
                            video.playbackRate = 1.0;
                            window.__ytAdMuted = false;
                        }
                    }

                    if (location.hostname.includes('facebook.com')) {
                        var spans = document.querySelectorAll('span, a');
                        for (var i = 0; i < spans.length; i++) {
                            if (spans[i].textContent && spans[i].textContent.trim() === 'Sponsored') {
                                var post = spans[i].closest('article, [data-tracking-duration-id]');
                                if (post) post.style.display = 'none';
                            }
                        }
                    }
                }, 400);
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