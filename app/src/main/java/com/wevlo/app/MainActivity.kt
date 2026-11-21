package com.wevlo.app

import android.app.Activity
import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.os.Bundle
import android.view.KeyEvent
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.media3.common.MediaItem
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.hls.HlsMediaSource
import androidx.media3.exoplayer.source.MediaSource
import androidx.media3.exoplayer.source.MergingMediaSource
import androidx.media3.exoplayer.source.ProgressiveMediaSource
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
import org.schabi.newpipe.extractor.search.SearchInfo
import org.schabi.newpipe.extractor.stream.StreamInfo
import org.schabi.newpipe.extractor.stream.StreamInfoItem
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class MainActivity : Activity() {

    private lateinit var searchInput: EditText
    private lateinit var statusText: TextView
    private lateinit var resultsContainer: LinearLayout
    private lateinit var playerView: PlayerView
    private var player: ExoPlayer? = null

    private val executor = Executors.newSingleThreadExecutor()

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

        // ১. উপরের সার্চ বার
        val searchBar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setBackgroundColor(Color.parseColor("#181818"))
            setPadding(16, 16, 16, 16)
        }

        searchInput = EditText(this).apply {
            hint = "Search YouTube (Ad-Free)..."
            setTextColor(Color.WHITE)
            setHintTextColor(Color.GRAY)
            setBackgroundColor(Color.parseColor("#272727"))
            setPadding(24, 16, 24, 16)
            isSingleLine = true
            imeOptions = EditorInfo.IME_ACTION_SEARCH
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            setOnEditorActionListener { _, actionId, event ->
                if (actionId == EditorInfo.IME_ACTION_SEARCH ||
                    (event != null && event.keyCode == KeyEvent.KEYCODE_ENTER && event.action == KeyEvent.ACTION_DOWN)
                ) {
                    performSearch()
                    true
                } else {
                    false
                }
            }
        }

        val searchBtn = Button(this).apply {
            text = "Search"
            setOnClickListener { performSearch() }
        }

        searchBar.addView(searchInput)
        searchBar.addView(searchBtn)

        // ২. নিজস্ব ExoPlayer ভিডিও প্লেয়ার (16:9 সাইজ)
        val playerHeight = (resources.displayMetrics.widthPixels * 9) / 16
        playerView = PlayerView(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                playerHeight
            )
            setBackgroundColor(Color.BLACK)
        }

        player = ExoPlayer.Builder(this).build()
        playerView.player = player

        // ৩. ভিডিও টাইটেল ও স্ট্যাটাস
        statusText = TextView(this).apply {
            text = "যেকোনো কিছু সার্চ করে ভিডিও প্লে করুন (0% Ads)"
            setTextColor(Color.WHITE)
            textSize = 15f
            setTypeface(null, Typeface.BOLD)
            setBackgroundColor(Color.parseColor("#181818"))
            setPadding(24, 20, 24, 20)
        }

        // ৪. সার্চ রেজাল্ট লিস্ট
        val scrollView = ScrollView(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                0,
                1f
            )
        }

        resultsContainer = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(16, 12, 16, 24)
        }
        scrollView.addView(resultsContainer)

        root.addView(searchBar)
        root.addView(playerView)
        root.addView(statusText)
        root.addView(scrollView)
        setContentView(root)

        // শুরুতে কিছু ভিডিও লোড করা
        searchYouTube("nature 4k")
    }

    private fun performSearch() {
        val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
        imm.hideSoftInputFromWindow(searchInput.windowToken, 0)
        searchInput.clearFocus()

        val query = searchInput.text.toString().trim()
        if (query.isNotEmpty()) {
            searchYouTube(query)
        }
    }

    private fun searchYouTube(query: String) {
        statusText.text = "Searching: $query ..."
        resultsContainer.removeAllViews()

        executor.execute {
            try {
                val ytService = ServiceList.YouTube
                val queryHandler = ytService.searchQHFactory.fromQuery(query, listOf("videos"), "")
                val searchInfo = SearchInfo.getInfo(ytService, queryHandler)
                val videos = searchInfo.relatedItems.filterIsInstance<StreamInfoItem>()

                runOnUiThread {
                    statusText.text = "Found ${videos.size} videos — ট্যাপ করে প্লে করুন"
                    showVideoList(videos)
                }
            } catch (e: Exception) {
                runOnUiThread {
                    statusText.text = "Search error: ${e.localizedMessage}"
                }
            }
        }
    }

    private fun showVideoList(videos: List<StreamInfoItem>) {
        resultsContainer.removeAllViews()
        for (item in videos) {
            val card = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setBackgroundColor(Color.parseColor("#1E1E1E"))
                setPadding(24, 20, 24, 20)
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                ).apply {
                    setMargins(0, 0, 0, 14)
                }
                setOnClickListener {
                    playYouTubeVideo(item.url, item.name)
                }
            }

            val titleView = TextView(this).apply {
                text = item.name
                setTextColor(Color.WHITE)
                textSize = 15f
                setTypeface(null, Typeface.BOLD)
            }

            val durationStr = if (item.duration > 0) {
                val min = item.duration / 60
                val sec = item.duration % 60
                String.format("%d:%02d", min, sec)
            } else {
                "LIVE"
            }

            val metaView = TextView(this).apply {
                text = "${item.uploaderName ?: "Unknown"}  •  $durationStr"
                setTextColor(Color.LTGRAY)
                textSize = 12f
                setPadding(0, 8, 0, 0)
            }

            card.addView(titleView)
            card.addView(metaView)
            resultsContainer.addView(card)
        }
    }

    @OptIn(UnstableApi::class)
    private fun playYouTubeVideo(videoUrl: String, title: String) {
        statusText.text = "Loading stream: $title ..."

        executor.execute {
            try {
                val info = StreamInfo.getInfo(ServiceList.YouTube, videoUrl)

                // সেরা Video-Only ও Audio স্ট্রিম বেছে নেওয়া (720p/1080p সাপোর্টের জন্য)
                val bestVideoOnly = info.videoOnlyStreams
                    .filter { it.isUrl }
                    .firstOrNull { it.resolution.contains("720") || it.resolution.contains("480") }
                    ?: info.videoOnlyStreams.firstOrNull { it.isUrl }

                val bestAudio = info.audioStreams
                    .filter { it.isUrl }
                    .maxByOrNull { it.averageBitrate }

                val bestMuxed = info.videoStreams.firstOrNull { it.isUrl }

                val dataSourceFactory = DefaultHttpDataSource.Factory()
                    .setUserAgent("Mozilla/5.0 (Windows NT 10.0; Win64; x64; rv:140.0) Gecko/20100101 Firefox/140.0")

                val mediaSource: MediaSource? = when {
                    bestVideoOnly != null && bestAudio != null -> {
                        val videoSource = ProgressiveMediaSource.Factory(dataSourceFactory)
                            .createMediaSource(MediaItem.fromUri(bestVideoOnly.content))
                        val audioSource = ProgressiveMediaSource.Factory(dataSourceFactory)
                            .createMediaSource(MediaItem.fromUri(bestAudio.content))
                        MergingMediaSource(videoSource, audioSource)
                    }
                    bestMuxed != null -> {
                        ProgressiveMediaSource.Factory(dataSourceFactory)
                            .createMediaSource(MediaItem.fromUri(bestMuxed.content))
                    }
                    !info.hlsUrl.isNullOrEmpty() -> {
                        HlsMediaSource.Factory(dataSourceFactory)
                            .createMediaSource(MediaItem.fromUri(info.hlsUrl))
                    }
                    else -> null
                }

                runOnUiThread {
                    if (mediaSource != null) {
                        statusText.text = "Playing (Ad-Free): $title"
                        player?.apply {
                            setMediaSource(mediaSource)
                            prepare()
                            playWhenReady = true
                        }
                    } else {
                        statusText.text = "এই ভিডিওর স্ট্রিম পাওয়া যায়নি।"
                    }
                }
            } catch (e: Exception) {
                runOnUiThread {
                    statusText.text = "Play error: ${e.localizedMessage}"
                }
            }
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