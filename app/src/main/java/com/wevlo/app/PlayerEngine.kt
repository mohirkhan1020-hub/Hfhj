// Created with Wevlo Studio
package com.wevlo.app

import android.annotation.SuppressLint
import android.app.DownloadManager
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat as AndroidMediaFormat
import android.media.MediaMuxer
import android.media.session.MediaSession
import android.media.session.PlaybackState
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.os.IBinder
import android.provider.MediaStore
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import okhttp3.OkHttpClient
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import org.schabi.newpipe.extractor.MediaFormat
import org.schabi.newpipe.extractor.NewPipe
import org.schabi.newpipe.extractor.ServiceList
import org.schabi.newpipe.extractor.downloader.Downloader
import org.schabi.newpipe.extractor.downloader.Request
import org.schabi.newpipe.extractor.downloader.Response
import org.schabi.newpipe.extractor.exceptions.ReCaptchaException
import org.schabi.newpipe.extractor.search.SearchInfo
import org.schabi.newpipe.extractor.stream.AudioStream
import org.schabi.newpipe.extractor.stream.StreamInfo
import org.schabi.newpipe.extractor.stream.StreamInfoItem
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.nio.ByteBuffer
import java.util.concurrent.TimeUnit

data class VideoItemUi(
    val id: String,
    val url: String,
    val title: String,
    val channelName: String,
    val thumbnailUrl: String,
    val avatarUrl: String?,
    val durationSec: Long,
    val viewCount: Long,
    val uploadDate: String?
)

data class VideoQualityOption(
    val label: String,
    val height: Int,
    val videoUrl: String,
    val audioUrl: String?,
    val isHls: Boolean = false
)

data class SubtitleOption(
    val label: String,
    val url: String
)

data class SubtitleCue(
    val startMs: Long,
    val endMs: Long,
    val text: String
)

data class SponsorSegment(
    val startMs: Long,
    val endMs: Long,
    val category: String
)

data class LoadedStreamBundle(
    val title: String,
    val channelName: String,
    val avatarUrl: String?,
    val viewsText: String,
    val description: String,
    val qualities: List<VideoQualityOption>,
    val subtitles: List<SubtitleOption>,
    val relatedVideos: List<VideoItemUi>,
    val bestHdVideoUrl: String?,
    val bestHdLabel: String,
    val bestMuxedUrl: String?,
    val bestAudioM4aUrl: String?,
    val historyItem: VideoItemUi
)

object YouTubeBackend {

    const val USER_AGENT =
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64; rv:140.0) Gecko/20100101 Firefox/140.0"

    fun init() {
        NewPipe.init(OkHttpDownloader.getInstance())
    }

    fun extractYouTubeVideoId(url: String): String? {
        val uri = Uri.parse(url)
        val host = uri.host?.lowercase() ?: return null
        if (!host.contains("youtube.com") && !host.contains("youtu.be")) return null
        if (host.contains("youtu.be")) {
            return uri.pathSegments.firstOrNull()?.takeIf { it.isNotBlank() }
        }
        uri.getQueryParameter("v")?.takeIf { it.isNotBlank() }?.let { return it }
        val segs = uri.pathSegments
        if (segs.size >= 2 && (segs[0] == "shorts" || segs[0] == "embed" || segs[0] == "live")) {
            return segs[1].takeIf { it.isNotBlank() }
        }
        return null
    }

    fun searchVideos(query: String): List<VideoItemUi> {
        val yt = ServiceList.YouTube
        val handler = yt.searchQHFactory.fromQuery(query, listOf("videos"), "")
        val info = SearchInfo.getInfo(yt, handler)
        return info.relatedItems.filterIsInstance<StreamInfoItem>().map { it.toUiItem() }
    }

    fun loadStreamBundle(videoId: String): LoadedStreamBundle {
        val watchUrl = "https://www.youtube.com/watch?v=$videoId"
        val info = StreamInfo.getInfo(ServiceList.YouTube, watchUrl)

        val bestAudio: AudioStream? = info.audioStreams
            .filter { it.isUrl }
            .maxByOrNull { it.averageBitrate }

        val m4aAudio = info.audioStreams
            .filter { it.isUrl && it.format == MediaFormat.M4A }
            .maxByOrNull { it.averageBitrate } ?: bestAudio

        val hdMp4 = info.videoOnlyStreams
            .filter { it.isUrl && it.format == MediaFormat.MPEG_4 }
            .sortedByDescending { parseHeight(it.resolution) }
            .firstOrNull { parseHeight(it.resolution) <= 1080 }

        val options = mutableListOf<VideoQualityOption>()
        if (bestAudio != null) {
            val seenHeights = HashSet<Int>()
            val sorted = info.videoOnlyStreams
                .filter { it.isUrl }
                .sortedByDescending { parseHeight(it.resolution) }

            for (vs in sorted) {
                val h = parseHeight(vs.resolution)
                if (h > 0 && seenHeights.add(h)) {
                    options.add(VideoQualityOption("${h}p", h, vs.content, bestAudio.content))
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

        val subs = info.subtitles
            .filter { it.format == MediaFormat.VTT }
            .distinctBy { it.displayLanguageName }
            .map { SubtitleOption(it.displayLanguageName, it.content) }

        val related = info.relatedItems.filterIsInstance<StreamInfoItem>().map { it.toUiItem() }
        val thumb = info.thumbnails.maxByOrNull { it.height }?.url ?: ""
        val avatar = info.uploaderAvatars.firstOrNull()?.url
        val channelName = info.uploaderName ?: "YouTube"

        val viewsText = buildList {
            if (info.viewCount > 0) add("${formatCount(info.viewCount)} views")
            if (!info.textualUploadDate.isNullOrBlank()) add(info.textualUploadDate)
        }.joinToString("  •  ")

        val historyItem = VideoItemUi(
            id = videoId,
            url = watchUrl,
            title = info.name,
            channelName = channelName,
            thumbnailUrl = thumb,
            avatarUrl = avatar,
            durationSec = info.duration,
            viewCount = info.viewCount,
            uploadDate = info.textualUploadDate
        )

        return LoadedStreamBundle(
            title = info.name,
            channelName = channelName,
            avatarUrl = avatar,
            viewsText = viewsText,
            description = info.description?.content ?: "",
            qualities = options,
            subtitles = subs,
            relatedVideos = related,
            bestHdVideoUrl = hdMp4?.content,
            bestHdLabel = hdMp4?.resolution ?: "HD",
            bestMuxedUrl = info.videoStreams.firstOrNull { it.isUrl }?.content,
            bestAudioM4aUrl = m4aAudio?.content,
            historyItem = historyItem
        )
    }

    private fun StreamInfoItem.toUiItem(): VideoItemUi {
        val id = extractYouTubeVideoId(this.url) ?: this.url
        val thumb = this.thumbnails.maxByOrNull { it.height }?.url
            ?: this.thumbnails.firstOrNull()?.url ?: ""
        val avatar = this.uploaderAvatars.firstOrNull()?.url
        return VideoItemUi(
            id = id,
            url = this.url,
            title = this.name,
            channelName = this.uploaderName ?: "YouTube",
            thumbnailUrl = thumb,
            avatarUrl = avatar,
            durationSec = this.duration,
            viewCount = this.viewCount,
            uploadDate = this.textualUploadDate
        )
    }

    fun fetchSponsorSegments(videoId: String): List<SponsorSegment> {
        val list = mutableListOf<SponsorSegment>()
        try {
            val apiUrl = "https://sponsor.ajay.app/api/skipSegments?videoID=$videoId&categories=%5B%22sponsor%22,%22selfpromo%22,%22interaction%22,%22intro%22,%22outro%22%5D"
            val conn = (URL(apiUrl).openConnection() as HttpURLConnection).apply {
                connectTimeout = 4000
                readTimeout = 4000
            }
            if (conn.responseCode == 200) {
                val arr = JSONArray(conn.inputStream.bufferedReader().readText())
                for (i in 0 until arr.length()) {
                    val obj = arr.getJSONObject(i)
                    val seg = obj.getJSONArray("segment")
                    val startMs = (seg.getDouble(0) * 1000).toLong()
                    val endMs = (seg.getDouble(1) * 1000).toLong()
                    val cat = obj.optString("category", "sponsor")
                    if (endMs > startMs + 500) list.add(SponsorSegment(startMs, endMs, cat))
                }
            }
            conn.disconnect()
        } catch (_: Exception) {
        }
        return list
    }

    fun fetchDislikeStats(videoId: String): Pair<String, String>? {
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
                likes to dislikes
            } else {
                conn.disconnect()
                null
            }
        } catch (_: Exception) {
            null
        }
    }

    fun fetchAndParseVtt(url: String): List<SubtitleCue> {
        return try {
            val conn = (URL(url).openConnection() as HttpURLConnection).apply {
                connectTimeout = 5000
                readTimeout = 5000
            }
            val result = if (conn.responseCode == 200) {
                parseVtt(conn.inputStream.bufferedReader().readText())
            } else {
                emptyList()
            }
            conn.disconnect()
            result
        } catch (_: Exception) {
            emptyList()
        }
    }

    private fun parseVtt(vttText: String): List<SubtitleCue> {
        val cues = mutableListOf<SubtitleCue>()
        val blocks = vttText.replace("\r\n", "\n").split("\n\n")
        for (block in blocks) {
            val lines = block.lines().filter { it.isNotBlank() }
            val timeIdx = lines.indexOfFirst { it.contains("-->") }
            if (timeIdx == -1) continue
            val parts = lines[timeIdx].split("-->")
            if (parts.size < 2) continue
            val startMs = parseVttTime(parts[0].trim())
            val endMs = parseVttTime(parts[1].trim().substringBefore(" "))
            val text = lines.drop(timeIdx + 1)
                .joinToString("\n")
                .replace(Regex("<[^>]*>"), "")
                .trim()
            if (endMs > startMs && text.isNotEmpty()) {
                cues.add(SubtitleCue(startMs, endMs, text))
            }
        }
        return cues
    }

    private fun parseVttTime(t: String): Long {
        val clean = t.replace(",", ".").trim()
        val parts = clean.split(":")
        return try {
            when (parts.size) {
                3 -> {
                    val h = parts[0].toLong()
                    val m = parts[1].toLong()
                    val s = (parts[2].toDouble() * 1000).toLong()
                    (h * 3600 + m * 60) * 1000 + s
                }
                2 -> {
                    val m = parts[0].toLong()
                    val s = (parts[1].toDouble() * 1000).toLong()
                    (m * 60) * 1000 + s
                }
                else -> 0L
            }
        } catch (_: Exception) {
            0L
        }
    }

    fun downloadAndMuxHdMp4(
        context: Context,
        videoUrl: String,
        audioUrl: String,
        title: String,
        onProgress: (String?) -> Unit,
        onDone: (String) -> Unit,
        onError: (String) -> Unit
    ) {
        val tempV = File(context.cacheDir, "dl_v_${System.currentTimeMillis()}.mp4")
        val tempA = File(context.cacheDir, "dl_a_${System.currentTimeMillis()}.m4a")
        val tempOut = File(context.cacheDir, "dl_out_${System.currentTimeMillis()}.mp4")
        try {
            onProgress("Downloading HD Video...")
            downloadFileWithProgress(videoUrl, tempV, "Downloading Video", onProgress)

            onProgress("Downloading Audio...")
            downloadFileWithProgress(audioUrl, tempA, "Downloading Audio", onProgress)

            onProgress("Merging HD MP4 (MediaMuxer)...")
            muxVideoAndAudio(tempV.absolutePath, tempA.absolutePath, tempOut.absolutePath)

            val safeName = title.replace(Regex("[^a-zA-Z0-9._ -]"), "_").take(50)
            val finalName = "${safeName}_HD_${System.currentTimeMillis()}.mp4"
            saveFileToDownloads(context, tempOut, finalName, "video/mp4")

            onProgress(null)
            onDone(finalName)
        } catch (e: Exception) {
            onProgress(null)
            onError(e.localizedMessage ?: "Download failed")
        } finally {
            tempV.delete()
            tempA.delete()
            tempOut.delete()
        }
    }

    private fun downloadFileWithProgress(
        urlStr: String,
        dest: File,
        label: String,
        onProgress: (String?) -> Unit
    ) {
        val conn = (URL(urlStr).openConnection() as HttpURLConnection).apply {
            setRequestProperty("User-Agent", USER_AGENT)
            connectTimeout = 15000
            readTimeout = 30000
        }
        val total = conn.contentLengthLong
        conn.inputStream.use { input ->
            FileOutputStream(dest).use { output ->
                val buf = ByteArray(16384)
                var copied = 0L
                var lastPct = -1
                while (true) {
                    val read = input.read(buf)
                    if (read == -1) break
                    output.write(buf, 0, read)
                    copied += read
                    if (total > 0) {
                        val pct = ((copied * 100) / total).toInt()
                        if (pct != lastPct && pct % 5 == 0) {
                            lastPct = pct
                            onProgress("$label: $pct%")
                        }
                    }
                }
            }
        }
        conn.disconnect()
    }

    @SuppressLint("WrongConstant")
    private fun muxVideoAndAudio(videoPath: String, audioPath: String, outPath: String) {
        val muxer = MediaMuxer(outPath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
        val vExt = MediaExtractor().apply { setDataSource(videoPath) }
        val aExt = MediaExtractor().apply { setDataSource(audioPath) }

        var vOutTrack = -1
        for (i in 0 until vExt.trackCount) {
            val fmt = vExt.getTrackFormat(i)
            val mime = fmt.getString(AndroidMediaFormat.KEY_MIME) ?: ""
            if (mime.startsWith("video/")) {
                vExt.selectTrack(i)
                vOutTrack = muxer.addTrack(fmt)
                break
            }
        }

        var aOutTrack = -1
        for (i in 0 until aExt.trackCount) {
            val fmt = aExt.getTrackFormat(i)
            val mime = fmt.getString(AndroidMediaFormat.KEY_MIME) ?: ""
            if (mime.startsWith("audio/")) {
                aExt.selectTrack(i)
                aOutTrack = muxer.addTrack(fmt)
                break
            }
        }

        muxer.start()
        val buffer = ByteBuffer.allocate(1024 * 1024)
        val info = MediaCodec.BufferInfo()

        if (vOutTrack != -1) {
            while (true) {
                val size = vExt.readSampleData(buffer, 0)
                if (size < 0) break
                info.offset = 0
                info.size = size
                info.presentationTimeUs = vExt.sampleTime
                info.flags = vExt.sampleFlags
                muxer.writeSampleData(vOutTrack, buffer, info)
                vExt.advance()
            }
        }

        if (aOutTrack != -1) {
            while (true) {
                val size = aExt.readSampleData(buffer, 0)
                if (size < 0) break
                info.offset = 0
                info.size = size
                info.presentationTimeUs = aExt.sampleTime
                info.flags = aExt.sampleFlags
                muxer.writeSampleData(aOutTrack, buffer, info)
                aExt.advance()
            }
        }

        muxer.stop()
        muxer.release()
        vExt.release()
        aExt.release()
    }

    private fun saveFileToDownloads(context: Context, source: File, fileName: String, mimeType: String) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val values = ContentValues().apply {
                put(MediaStore.Downloads.DISPLAY_NAME, fileName)
                put(MediaStore.Downloads.MIME_TYPE, mimeType)
                put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS)
                put(MediaStore.Downloads.IS_PENDING, 1)
            }
            val resolver = context.contentResolver
            val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values) ?: return
            resolver.openOutputStream(uri)?.use { out ->
                FileInputStream(source).use { it.copyTo(out) }
            }
            values.clear()
            values.put(MediaStore.Downloads.IS_PENDING, 0)
            resolver.update(uri, values, null, null)
        } else {
            @Suppress("DEPRECATION")
            val dir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
            dir.mkdirs()
            source.copyTo(File(dir, fileName), overwrite = true)
        }
    }

    fun startSystemDownload(context: Context, url: String, title: String, ext: String): String {
        val safeName = title.replace(Regex("[^a-zA-Z0-9._ -]"), "_").take(50)
        val fileName = "${safeName}_${System.currentTimeMillis()}.$ext"
        val req = DownloadManager.Request(Uri.parse(url))
            .setTitle(title)
            .setDescription("Downloading $ext...")
            .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
            .setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, fileName)
        (context.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager).enqueue(req)
        return fileName
    }

    private fun parseHeight(resolution: String?): Int {
        if (resolution.isNullOrBlank()) return 0
        return Regex("(\\d+)").find(resolution)?.groupValues?.get(1)?.toIntOrNull() ?: 0
    }

    fun formatCount(n: Long): String = when {
        n >= 1_000_000 -> String.format("%.1fM", n / 1_000_000.0)
        n >= 1_000 -> String.format("%.1fK", n / 1_000.0)
        else -> n.toString()
    }

    fun formatDuration(sec: Long): String {
        if (sec <= 0) return "LIVE"
        val h = sec / 3600
        val m = (sec % 3600) / 60
        val s = sec % 60
        return if (h > 0) String.format("%d:%02d:%02d", h, m, s) else String.format("%d:%02d", m, s)
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
        var onPlaybackEnded: (() -> Unit)? = null
        var onIsPlayingChanged: ((Boolean) -> Unit)? = null

        @androidx.annotation.OptIn(UnstableApi::class)
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
                onIsPlayingChanged?.invoke(isPlaying)
                refreshNotification()
            }

            override fun onPlaybackStateChanged(playbackState: Int) {
                if (playbackState == Player.STATE_ENDED) {
                    onPlaybackEnded?.invoke()
                }
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
        player?.clearMediaItems()
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
        val openAppIntent = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), piFlags)
        val toggleIntent = PendingIntent.getService(
            this, 1, Intent(this, BgPlaybackService::class.java).apply { action = ACTION_TOGGLE }, piFlags
        )
        val stopIntent = PendingIntent.getService(
            this, 2, Intent(this, BgPlaybackService::class.java).apply { action = ACTION_STOP }, piFlags
        )

        val builder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(CHANNEL_ID, "Background Audio", NotificationManager.IMPORTANCE_LOW)
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
            builder.header("User-Agent", YouTubeBackend.USER_AGENT)
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