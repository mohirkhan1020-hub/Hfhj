package com.wevlo.app

import android.Manifest
import android.annotation.SuppressLint
import android.app.PictureInPictureParams
import android.content.Context
import android.content.Intent
import android.content.pm.ActivityInfo
import android.content.pm.PackageManager
import android.media.AudioManager
import android.os.Build
import android.os.Bundle
import android.util.Rational
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.View
import android.view.WindowInsets
import android.view.WindowInsetsController
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.ClosedCaption
import androidx.compose.material.icons.rounded.DeleteOutline
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material.icons.rounded.Headphones
import androidx.compose.material.icons.rounded.HighQuality
import androidx.compose.material.icons.rounded.History
import androidx.compose.material.icons.rounded.Home
import androidx.compose.material.icons.rounded.KeyboardArrowDown
import androidx.compose.material.icons.rounded.KeyboardArrowUp
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material.icons.rounded.LockOpen
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PictureInPictureAlt
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Repeat
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.Shield
import androidx.compose.material.icons.rounded.SkipNext
import androidx.compose.material.icons.rounded.Speed
import androidx.compose.material.icons.rounded.ThumbDown
import androidx.compose.material.icons.rounded.ThumbUp
import androidx.compose.material.icons.rounded.ZoomOutMap
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
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
import coil.compose.AsyncImage
import kotlinx.coroutines.delay
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.Executors
import kotlin.math.abs

private val BgDark = Color(0xFF0B0B0F)
private val SurfaceDark = Color(0xFF14141C)
private val CardDark = Color(0xFF1C1C26)
private val ChipBorder = Color(0xFF2E2E3E)
private val AccentRed = Color(0xFFFF2E4C)
private val AccentGreen = Color(0xFF4ADE80)
private val AccentBlue = Color(0xFF60A5FA)
private val TextSecondary = Color(0xFFA1A1B5)

class MainActivity : ComponentActivity() {

    private val executor = Executors.newFixedThreadPool(4)
    private var playerViewRef: PlayerView? = null

    private var selectedTab by mutableIntStateOf(0)
    private var selectedCategory by mutableStateOf("Popular")
    private var searchQuery by mutableStateOf("")
    private var homeVideos by mutableStateOf<List<VideoItemUi>>(emptyList())
    private var searchVideos by mutableStateOf<List<VideoItemUi>>(emptyList())
    private var historyVideos by mutableStateOf<List<VideoItemUi>>(emptyList())
    private var isLoadingFeed by mutableStateOf(false)
    private var feedError by mutableStateOf<String?>(null)

    private var isPlayerVisible by mutableStateOf(false)
    private var isPlayingNow by mutableStateOf(false)
    private var isLoadingStream by mutableStateOf(false)
    private var activeVideoId by mutableStateOf<String?>(null)
    private var activeTitle by mutableStateOf("WevloTube")
    private var activeChannel by mutableStateOf("")
    private var activeAvatarUrl by mutableStateOf<String?>(null)
    private var activeViewsText by mutableStateOf("")
    private var activeDescription by mutableStateOf("")
    private var activeVotesText by mutableStateOf<String?>(null)
    private var activeDislikesText by mutableStateOf<String?>(null)
    private var sponsorCount by mutableIntStateOf(0)
    private var relatedVideos by mutableStateOf<List<VideoItemUi>>(emptyList())

    private var availableQualities by mutableStateOf<List<VideoQualityOption>>(emptyList())
    private var currentQualityLabel by mutableStateOf("Auto")
    private var currentSpeed by mutableFloatStateOf(1.0f)
    private var isFullscreen by mutableStateOf(false)
    private var isPipMode by mutableStateOf(false)
    private var isTouchLocked by mutableStateOf(false)
    private var isLooping by mutableStateOf(false)
    private var isZoomed by mutableStateOf(false)
    private var autoPlayNext by mutableStateOf(true)
    private var bgPlayEnabled by mutableStateOf(true)
    private var showDescription by mutableStateOf(false)

    private var availableSubtitles by mutableStateOf<List<SubtitleOption>>(emptyList())
    private var currentSubtitleLabel by mutableStateOf("Off")
    private var activeCues by mutableStateOf<List<SubtitleCue>>(emptyList())
    private var currentSubtitleText by mutableStateOf("")

    private var bestHdVideoUrl by mutableStateOf<String?>(null)
    private var bestHdLabel by mutableStateOf("1080p")
    private var bestMuxedUrl by mutableStateOf<String?>(null)
    private var bestAudioM4aUrl by mutableStateOf<String?>(null)
    private var downloadStatusText by mutableStateOf<String?>(null)

    private var showQualityDialog by mutableStateOf(false)
    private var showSpeedDialog by mutableStateOf(false)
    private var showSubtitleDialog by mutableStateOf(false)
    private var showDownloadDialog by mutableStateOf(false)
    private var gestureText by mutableStateOf<String?>(null)
    private var gestureTriggerTime by mutableStateOf(0L)

    @Volatile
    private var sponsorSegments: List<SponsorSegment> = emptyList()
    private var lastSkippedEndMs: Long = -1L
    private var brightnessRatio = 0.5f
    private var volumeRatio = 0.5f

    private val categories = listOf("Popular", "Music", "Gaming", "Tech", "Bangla", "News", "Sports")

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        window.statusBarColor = 0xFF0B0B0F.toInt()
        window.navigationBarColor = 0xFF0B0B0F.toInt()

        YouTubeBackend.init()
        val player = BgPlaybackService.ensurePlayer(applicationContext)
        isPlayingNow = player.isPlaying

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
                requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 101)
            }
        }

        val audioManager = getSystemService(Context.AUDIO_SERVICE) as AudioManager
        val maxVol = audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC).coerceAtLeast(1)
        volumeRatio = audioManager.getStreamVolume(AudioManager.STREAM_MUSIC).toFloat() / maxVol

        addOnPictureInPictureModeChangedListener { info ->
            isPipMode = info.isInPictureInPictureMode
            playerViewRef?.useController = !info.isInPictureInPictureMode && !isTouchLocked
        }

        BgPlaybackService.onIsPlayingChanged = { playing ->
            runOnUiThread { isPlayingNow = playing }
        }

        BgPlaybackService.onCloseFromNotification = {
            runOnUiThread { isPlayerVisible = false }
        }

        BgPlaybackService.onPlaybackEnded = {
            runOnUiThread {
                if (autoPlayNext && !isLooping && relatedVideos.isNotEmpty()) {
                    val next = relatedVideos.first()
                    Toast.makeText(this, "Up Next: ${next.title}", Toast.LENGTH_SHORT).show()
                    playVideoItem(next)
                }
            }
        }

        loadHistoryFromPrefs()
        loadFeedForCategory(selectedCategory)
        handleIncomingIntent(intent)

        setContent {
            MaterialTheme(
                colorScheme = darkColorScheme(
                    primary = AccentRed,
                    background = BgDark,
                    surface = SurfaceDark,
                    onBackground = Color.White,
                    onSurface = Color.White
                )
            ) {
                WevloTubeApp(player)
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
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
        val videoId = YouTubeBackend.extractYouTubeVideoId(urlMatch)
        if (videoId != null) {
            playYouTubeById(videoId, "Shared Video", "", null)
        }
    }

    @Composable
    private fun WevloTubeApp(player: ExoPlayer) {
        LaunchedEffect(activeVideoId, activeCues) {
            while (true) {
                if (player.isPlaying) {
                    val pos = player.currentPosition
                    for (seg in sponsorSegments) {
                        if (pos in seg.startMs until (seg.endMs - 400) && lastSkippedEndMs != seg.endMs) {
                            lastSkippedEndMs = seg.endMs
                            player.seekTo(seg.endMs)
                            showGesture("Skipped ${seg.category}")
                            break
                        }
                    }
                    if (activeCues.isNotEmpty()) {
                        val cue = activeCues.firstOrNull { pos in it.startMs..it.endMs }
                        currentSubtitleText = cue?.text ?: ""
                    } else if (currentSubtitleText.isNotEmpty()) {
                        currentSubtitleText = ""
                    }
                }
                delay(250)
            }
        }

        LaunchedEffect(gestureTriggerTime) {
            if (gestureText != null) {
                delay(950)
                if (System.currentTimeMillis() - gestureTriggerTime >= 900) {
                    gestureText = null
                }
            }
        }

        BackHandler {
            when {
                isTouchLocked -> showGesture("Locked — Tap Unlock")
                isFullscreen -> toggleFullscreen(false)
                isPlayerVisible -> isPlayerVisible = false
                selectedTab != 0 -> selectedTab = 0
                else -> finish()
            }
        }

        if (isPipMode || isFullscreen) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color.Black)
            ) {
                NativeVideoPlayerBox(player, Modifier.fillMaxSize())
            }
        } else {
            Scaffold(
                containerColor = BgDark,
                topBar = {
                    if (!isPlayerVisible) TopBrandBar()
                },
                bottomBar = {
                    if (!isPlayerVisible) {
                        NavigationBar(containerColor = SurfaceDark) {
                            NavigationBarItem(
                                selected = selectedTab == 0,
                                onClick = { selectedTab = 0 },
                                icon = { Icon(Icons.Rounded.Home, contentDescription = "Home") },
                                label = { Text("Home") },
                                colors = NavigationBarItemDefaults.colors(
                                    selectedIconColor = AccentRed,
                                    selectedTextColor = AccentRed,
                                    indicatorColor = Color(0xFF2B161E)
                                )
                            )
                            NavigationBarItem(
                                selected = selectedTab == 1,
                                onClick = { selectedTab = 1 },
                                icon = { Icon(Icons.Rounded.Search, contentDescription = "Search") },
                                label = { Text("Search") },
                                colors = NavigationBarItemDefaults.colors(
                                    selectedIconColor = AccentRed,
                                    selectedTextColor = AccentRed,
                                    indicatorColor = Color(0xFF2B161E)
                                )
                            )
                            NavigationBarItem(
                                selected = selectedTab == 2,
                                onClick = { selectedTab = 2 },
                                icon = { Icon(Icons.Rounded.History, contentDescription = "History") },
                                label = { Text("History") },
                                colors = NavigationBarItemDefaults.colors(
                                    selectedIconColor = AccentRed,
                                    selectedTextColor = AccentRed,
                                    indicatorColor = Color(0xFF2B161E)
                                )
                            )
                        }
                    }
                }
            ) { paddingValues ->
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(paddingValues)
                ) {
                    if (isPlayerVisible) {
                        PlayerDetailsScreen(player)
                    } else {
                        when (selectedTab) {
                            0 -> HomeFeedScreen()
                            1 -> SearchFeedScreen()
                            else -> HistoryFeedScreen()
                        }
                    }

                    if (!isPlayerVisible && player.currentMediaItem != null) {
                        MiniPlayerBar(
                            player = player,
                            modifier = Modifier
                                .align(Alignment.BottomCenter)
                                .padding(8.dp)
                        )
                    }
                }
            }
        }

        if (showQualityDialog) QualityPickerDialog()
        if (showSpeedDialog) SpeedPickerDialog()
        if (showSubtitleDialog) SubtitlePickerDialog()
        if (showDownloadDialog) DownloadPickerDialog()
    }

    @Composable
    private fun TopBrandBar() {
        Surface(color = SurfaceDark, shadowElevation = 4.dp) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(
                    modifier = Modifier
                        .size(32.dp)
                        .clip(RoundedCornerShape(8.dp))
                        .background(AccentRed),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(Icons.Rounded.PlayArrow, contentDescription = null, tint = Color.White, modifier = Modifier.size(22.dp))
                }
                Spacer(Modifier.width(10.dp))
                Text("WevloTube", fontSize = 19.sp, fontWeight = FontWeight.Bold, color = Color.White)
                Spacer(Modifier.width(8.dp))
                Surface(
                    color = Color(0xFF162E21),
                    shape = RoundedCornerShape(10.dp),
                    border = BorderStroke(1.dp, Color(0xFF22543D))
                ) {
                    Text(
                        "AD-FREE",
                        color = AccentGreen,
                        fontSize = 10.sp,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.padding(horizontal = 7.dp, vertical = 2.dp)
                    )
                }
                Spacer(Modifier.weight(1f))
                IconButton(onClick = { selectedTab = 1 }) {
                    Icon(Icons.Rounded.Search, contentDescription = "Search", tint = Color.White)
                }
            }
        }
    }

    @Composable
    private fun HomeFeedScreen() {
        Column(modifier = Modifier.fillMaxSize()) {
            LazyRow(
                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 10.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                items(categories) { cat ->
                    val selected = cat == selectedCategory
                    Surface(
                        shape = RoundedCornerShape(20.dp),
                        color = if (selected) AccentRed else CardDark,
                        border = BorderStroke(1.dp, if (selected) AccentRed else ChipBorder),
                        modifier = Modifier.clickable {
                            selectedCategory = cat
                            loadFeedForCategory(cat)
                        }
                    ) {
                        Text(
                            text = cat,
                            color = Color.White,
                            fontSize = 13.sp,
                            fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium,
                            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
                        )
                    }
                }
            }
            VideoListBody(homeVideos, isLoadingFeed, feedError, "লোড হচ্ছে...")
        }
    }

    @Composable
    private fun SearchFeedScreen() {
        val keyboardController = LocalSoftwareKeyboardController.current
        Column(modifier = Modifier.fillMaxSize()) {
            OutlinedTextField(
                value = searchQuery,
                onValueChange = { searchQuery = it },
                placeholder = { Text("Search YouTube (Ad-Free)...", color = TextSecondary) },
                singleLine = true,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                keyboardActions = KeyboardActions(onSearch = {
                    keyboardController?.hide()
                    if (searchQuery.isNotBlank()) performSearch(searchQuery.trim())
                }),
                trailingIcon = {
                    IconButton(onClick = {
                        keyboardController?.hide()
                        if (searchQuery.isNotBlank()) performSearch(searchQuery.trim())
                    }) {
                        Icon(Icons.Rounded.Search, contentDescription = "Search", tint = AccentRed)
                    }
                },
                colors = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = AccentRed,
                    unfocusedBorderColor = ChipBorder,
                    focusedTextColor = Color.White,
                    unfocusedTextColor = Color.White,
                    focusedContainerColor = CardDark,
                    unfocusedContainerColor = CardDark
                ),
                shape = RoundedCornerShape(14.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(12.dp)
            )
            VideoListBody(searchVideos, isLoadingFeed, feedError, "উপরের বক্সে যেকোনো ভিডিও সার্চ করুন")
        }
    }

    @Composable
    private fun HistoryFeedScreen() {
        Column(modifier = Modifier.fillMaxSize()) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text("Watch History", color = Color.White, fontSize = 16.sp, fontWeight = FontWeight.Bold)
                Spacer(Modifier.weight(1f))
                if (historyVideos.isNotEmpty()) {
                    TextButton(onClick = { clearHistory() }) {
                        Icon(Icons.Rounded.DeleteOutline, contentDescription = null, tint = AccentRed)
                        Spacer(Modifier.width(4.dp))
                        Text("Clear", color = AccentRed)
                    }
                }
            }
            VideoListBody(historyVideos, false, null, "এখনো কোনো ভিডিও দেখা হয়নি")
        }
    }

    @Composable
    private fun VideoListBody(
        videos: List<VideoItemUi>,
        isLoading: Boolean,
        error: String?,
        emptyText: String
    ) {
        when {
            isLoading -> Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator(color = AccentRed)
            }
            error != null && videos.isEmpty() -> Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text(error, color = TextSecondary, textAlign = TextAlign.Center, modifier = Modifier.padding(24.dp))
            }
            videos.isEmpty() -> Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text(emptyText, color = TextSecondary)
            }
            else -> LazyColumn(
                contentPadding = PaddingValues(bottom = 80.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp)
            ) {
                items(videos, key = { it.id }) { video ->
                    VideoCard(video) { playVideoItem(video) }
                }
            }
        }
    }

    @Composable
    private fun VideoCard(video: VideoItemUi, onClick: () -> Unit) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .clickable { onClick() }
                .padding(horizontal = 12.dp)
        ) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .aspectRatio(16f / 9f)
                    .clip(RoundedCornerShape(12.dp))
                    .background(CardDark)
            ) {
                AsyncImage(
                    model = video.thumbnailUrl,
                    contentDescription = video.title,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize()
                )
                Surface(
                    color = Color(0xCC000000),
                    shape = RoundedCornerShape(6.dp),
                    modifier = Modifier
                        .align(Alignment.BottomEnd)
                        .padding(8.dp)
                ) {
                    Text(
                        text = YouTubeBackend.formatDuration(video.durationSec),
                        color = Color.White,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.SemiBold,
                        modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                    )
                }
            }

            Spacer(Modifier.height(10.dp))

            Row(verticalAlignment = Alignment.Top) {
                if (!video.avatarUrl.isNullOrBlank()) {
                    AsyncImage(
                        model = video.avatarUrl,
                        contentDescription = video.channelName,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier
                            .size(38.dp)
                            .clip(CircleShape)
                            .background(CardDark)
                    )
                } else {
                    Box(
                        modifier = Modifier
                            .size(38.dp)
                            .clip(CircleShape)
                            .background(CardDark),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(video.channelName.take(1).uppercase(), color = AccentRed, fontWeight = FontWeight.Bold)
                    }
                }

                Spacer(Modifier.width(12.dp))

                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = video.title,
                        color = Color.White,
                        fontSize = 14.sp,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis
                    )
                    Spacer(Modifier.height(3.dp))
                    val meta = buildList {
                        if (video.channelName.isNotBlank()) add(video.channelName)
                        if (video.viewCount > 0) add("${YouTubeBackend.formatCount(video.viewCount)} views")
                        if (!video.uploadDate.isNullOrBlank()) add(video.uploadDate)
                    }.joinToString("  •  ")
                    Text(meta, color = TextSecondary, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
        }
    }

    @Composable
    private fun PlayerDetailsScreen(player: ExoPlayer) {
        Column(modifier = Modifier.fillMaxSize()) {
            NativeVideoPlayerBox(
                player = player,
                modifier = Modifier
                    .fillMaxWidth()
                    .aspectRatio(16f / 9f)
            )

            if (downloadStatusText != null) {
                Surface(color = Color(0xFF1D263B), modifier = Modifier.fillMaxWidth()) {
                    Column(modifier = Modifier.padding(horizontal = 14.dp, vertical = 8.dp)) {
                        Text(downloadStatusText ?: "", color = AccentBlue, fontSize = 12.sp, fontWeight = FontWeight.Medium)
                        Spacer(Modifier.height(4.dp))
                        LinearProgressIndicator(modifier = Modifier.fillMaxWidth(), color = AccentBlue)
                    }
                }
            }

            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(bottom = 24.dp)
            ) {
                item {
                    Column(modifier = Modifier.padding(14.dp)) {
                        Text(activeTitle, color = Color.White, fontSize = 16.sp, fontWeight = FontWeight.Bold)
                        Spacer(Modifier.height(6.dp))

                        Row(verticalAlignment = Alignment.CenterVertically) {
                            if (!activeAvatarUrl.isNullOrBlank()) {
                                AsyncImage(
                                    model = activeAvatarUrl,
                                    contentDescription = activeChannel,
                                    contentScale = ContentScale.Crop,
                                    modifier = Modifier
                                        .size(30.dp)
                                        .clip(CircleShape)
                                        .background(CardDark)
                                )
                                Spacer(Modifier.width(8.dp))
                            }
                            Column(modifier = Modifier.weight(1f)) {
                                Text(activeChannel, color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                                if (activeViewsText.isNotBlank()) {
                                    Text(activeViewsText, color = TextSecondary, fontSize = 11.sp)
                                }
                            }
                            IconButton(onClick = { showDescription = !showDescription }) {
                                Icon(
                                    if (showDescription) Icons.Rounded.KeyboardArrowUp else Icons.Rounded.KeyboardArrowDown,
                                    contentDescription = "Description",
                                    tint = Color.White
                                )
                            }
                        }

                        Spacer(Modifier.height(8.dp))

                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                            if (activeVotesText != null) InfoBadgePill(Icons.Rounded.ThumbUp, activeVotesText!!, Color.White)
                            if (activeDislikesText != null) InfoBadgePill(Icons.Rounded.ThumbDown, activeDislikesText!!, Color.White)
                            InfoBadgePill(
                                Icons.Rounded.Shield,
                                if (sponsorCount > 0) "$sponsorCount skips" else "0% Ads",
                                AccentGreen
                            )
                        }

                        if (showDescription && activeDescription.isNotBlank()) {
                            Spacer(Modifier.height(10.dp))
                            Surface(color = CardDark, shape = RoundedCornerShape(10.dp), modifier = Modifier.fillMaxWidth()) {
                                Text(activeDescription, color = Color(0xFFD4D4E0), fontSize = 12.sp, modifier = Modifier.padding(12.dp))
                            }
                        }
                    }

                    LazyRow(
                        contentPadding = PaddingValues(horizontal = 14.dp, vertical = 4.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        item { ActionChip(Icons.Rounded.HighQuality, currentQualityLabel, true) { showQualityDialog = true } }
                        item { ActionChip(Icons.Rounded.Speed, "${currentSpeed}x") { showSpeedDialog = true } }
                        item {
                            ActionChip(
                                Icons.Rounded.ClosedCaption,
                                if (currentSubtitleLabel == "Off") "CC" else currentSubtitleLabel,
                                currentSubtitleLabel != "Off"
                            ) { showSubtitleDialog = true }
                        }
                        item {
                            ActionChip(Icons.Rounded.SkipNext, if (autoPlayNext) "AutoNext" else "No Next", autoPlayNext) {
                                autoPlayNext = !autoPlayNext
                            }
                        }
                        item {
                            ActionChip(Icons.Rounded.Repeat, if (isLooping) "Loop ON" else "Loop", isLooping) {
                                isLooping = !isLooping
                                player.repeatMode = if (isLooping) Player.REPEAT_MODE_ONE else Player.REPEAT_MODE_OFF
                            }
                        }
                        item {
                            ActionChip(Icons.Rounded.ZoomOutMap, if (isZoomed) "Zoom" else "Fit") {
                                isZoomed = !isZoomed
                                playerViewRef?.resizeMode = if (isZoomed) {
                                    AspectRatioFrameLayout.RESIZE_MODE_ZOOM
                                } else {
                                    AspectRatioFrameLayout.RESIZE_MODE_FIT
                                }
                            }
                        }
                        item { ActionChip(Icons.Rounded.PictureInPictureAlt, "PiP") { enterPipMode() } }
                        item {
                            ActionChip(Icons.Rounded.Headphones, if (bgPlayEnabled) "BG ON" else "BG OFF", bgPlayEnabled) {
                                bgPlayEnabled = !bgPlayEnabled
                                if (!bgPlayEnabled) {
                                    stopService(Intent(this@MainActivity, BgPlaybackService::class.java))
                                } else if (player.isPlaying) {
                                    startBgPlaybackService()
                                }
                            }
                        }
                        item { ActionChip(Icons.Rounded.Download, "Download HD") { showDownloadDialog = true } }
                        item { ActionChip(Icons.Rounded.Lock, "Lock") { setPlayerTouchLocked(true) } }
                        item { ActionChip(Icons.Rounded.KeyboardArrowDown, "Minimize") { isPlayerVisible = false } }
                        item { ActionChip(Icons.Rounded.Close, "Close") { closePlayer() } }
                    }

                    Spacer(Modifier.height(10.dp))
                    HorizontalDivider(color = ChipBorder)
                    Text(
                        text = "Up Next",
                        color = Color.White,
                        fontSize = 15.sp,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.padding(horizontal = 14.dp, vertical = 12.dp)
                    )
                }

                items(relatedVideos, key = { "rel_${it.id}" }) { rel ->
                    VideoCard(rel) { playVideoItem(rel) }
                    Spacer(Modifier.height(14.dp))
                }
            }
        }
    }

    @Composable
    private fun InfoBadgePill(icon: ImageVector, label: String, tint: Color) {
        Surface(color = CardDark, shape = RoundedCornerShape(16.dp), border = BorderStroke(1.dp, ChipBorder)) {
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp)) {
                Icon(icon, contentDescription = null, tint = tint, modifier = Modifier.size(14.dp))
                Spacer(Modifier.width(5.dp))
                Text(label, color = tint, fontSize = 11.sp, fontWeight = FontWeight.Medium)
            }
        }
    }

    @Composable
    private fun ActionChip(icon: ImageVector, label: String, isHighlighted: Boolean = false, onClick: () -> Unit) {
        Surface(
            color = if (isHighlighted) Color(0xFF2B161E) else CardDark,
            shape = RoundedCornerShape(20.dp),
            border = BorderStroke(1.dp, if (isHighlighted) AccentRed else ChipBorder),
            modifier = Modifier.clickable { onClick() }
        ) {
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp)) {
                Icon(icon, contentDescription = label, tint = if (isHighlighted) AccentRed else Color.White, modifier = Modifier.size(16.dp))
                Spacer(Modifier.width(6.dp))
                Text(label, color = Color.White, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
            }
        }
    }

    @Composable
    private fun MiniPlayerBar(player: ExoPlayer, modifier: Modifier = Modifier) {
        Surface(
            color = CardDark,
            shape = RoundedCornerShape(12.dp),
            border = BorderStroke(1.dp, ChipBorder),
            shadowElevation = 8.dp,
            modifier = modifier
                .fillMaxWidth()
                .clickable { isPlayerVisible = true }
        ) {
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp)) {
                Icon(Icons.Rounded.PlayArrow, contentDescription = null, tint = AccentRed)
                Spacer(Modifier.width(8.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(activeTitle, color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(activeChannel, color = TextSecondary, fontSize = 11.sp, maxLines = 1)
                }
                IconButton(onClick = { if (player.isPlaying) player.pause() else player.play() }) {
                    Icon(
                        if (isPlayingNow) Icons.Rounded.Pause else Icons.Rounded.PlayArrow,
                        contentDescription = "Play/Pause",
                        tint = Color.White
                    )
                }
                IconButton(onClick = { closePlayer() }) {
                    Icon(Icons.Rounded.Close, contentDescription = "Close", tint = Color.White)
                }
            }
        }
    }

    @SuppressLint("ClickableViewAccessibility")
    @androidx.annotation.OptIn(UnstableApi::class)
    @Composable
    private fun NativeVideoPlayerBox(player: ExoPlayer, modifier: Modifier) {
        Box(modifier = modifier.background(Color.Black)) {
            AndroidView(
                factory = { ctx ->
                    PlayerView(ctx).apply {
                        resizeMode = if (isZoomed) AspectRatioFrameLayout.RESIZE_MODE_ZOOM else AspectRatioFrameLayout.RESIZE_MODE_FIT
                        controllerShowTimeoutMs = 2500
                        setKeepContentOnPlayerReset(true)
                        setShowBuffering(PlayerView.SHOW_BUFFERING_WHEN_PLAYING)
                        setShowNextButton(false)
                        setShowPreviousButton(false)
                        setFullscreenButtonClickListener { shouldEnter -> toggleFullscreen(shouldEnter) }
                        this.player = player
                        attachGestureDetector(this)
                        playerViewRef = this
                    }
                },
                update = { pv ->
                    if (pv.player !== player) pv.player = player
                    pv.useController = !isPipMode && !isTouchLocked
                },
                modifier = Modifier.fillMaxSize()
            )

            if (isLoadingStream) {
                CircularProgressIndicator(color = AccentRed, modifier = Modifier.align(Alignment.Center))
            }

            if (currentSubtitleText.isNotEmpty() && !isPipMode) {
                Surface(
                    color = Color(0xBF000000),
                    shape = RoundedCornerShape(6.dp),
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .padding(horizontal = 24.dp, vertical = 22.dp)
                ) {
                    Text(
                        text = currentSubtitleText,
                        color = Color.White,
                        fontSize = 13.sp,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp)
                    )
                }
            }

            if (gestureText != null && !isPipMode) {
                Surface(
                    color = Color(0xCC14141E),
                    shape = RoundedCornerShape(20.dp),
                    border = BorderStroke(1.dp, Color(0x44FFFFFF)),
                    modifier = Modifier.align(Alignment.Center)
                ) {
                    Text(
                        text = gestureText ?: "",
                        color = Color.White,
                        fontSize = 14.sp,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.padding(horizontal = 18.dp, vertical = 9.dp)
                    )
                }
            }

            if (isTouchLocked && !isPipMode) {
                Surface(
                    color = AccentRed,
                    shape = RoundedCornerShape(20.dp),
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .padding(14.dp)
                        .clickable { setPlayerTouchLocked(false) }
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp)) {
                        Icon(Icons.Rounded.LockOpen, contentDescription = "Unlock", tint = Color.White, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(4.dp))
                        Text("Unlock", color = Color.White, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                    }
                }
            }
        }
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun attachGestureDetector(pv: PlayerView) {
        val gestureDetector = GestureDetector(this, object : GestureDetector.SimpleOnGestureListener() {
            override fun onDoubleTap(e: MotionEvent): Boolean {
                val p = BgPlaybackService.player ?: return false
                if (e.x < pv.width / 2f) {
                    p.seekBack()
                    showGesture("-10s")
                } else {
                    p.seekForward()
                    showGesture("+10s")
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
                val w = pv.width.toFloat().coerceAtLeast(1f)
                val h = pv.height.toFloat().coerceAtLeast(1f)
                val delta = distanceY / h

                if (e1.x < w / 2f) {
                    brightnessRatio = (brightnessRatio + delta).coerceIn(0.05f, 1.0f)
                    val lp = window.attributes
                    lp.screenBrightness = brightnessRatio
                    window.attributes = lp
                    showGesture("Brightness ${(brightnessRatio * 100).toInt()}%")
                } else {
                    val audioManager = getSystemService(Context.AUDIO_SERVICE) as AudioManager
                    val maxVol = audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC).coerceAtLeast(1)
                    volumeRatio = (volumeRatio + delta).coerceIn(0.0f, 1.0f)
                    audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, (volumeRatio * maxVol).toInt(), 0)
                    showGesture("Volume ${(volumeRatio * 100).toInt()}%")
                }
                return true
            }
        })

        pv.setOnTouchListener { _, event ->
            if (isTouchLocked) {
                if (event.action == MotionEvent.ACTION_UP) showGesture("Locked — Tap Unlock")
                return@setOnTouchListener true
            }
            gestureDetector.onTouchEvent(event)
            false
        }
    }

    private fun showGesture(msg: String) {
        gestureText = msg
        gestureTriggerTime = System.currentTimeMillis()
    }

    private fun setPlayerTouchLocked(locked: Boolean) {
        isTouchLocked = locked
        playerViewRef?.useController = !locked
        if (locked) {
            playerViewRef?.hideController()
            showGesture("Touch Locked")
        } else {
            playerViewRef?.showController()
            showGesture("Unlocked")
        }
    }

    @Composable
    private fun QualityPickerDialog() {
        AlertDialog(
            onDismissRequest = { showQualityDialog = false },
            containerColor = SurfaceDark,
            title = { Text("Video Quality", color = Color.White) },
            text = {
                LazyColumn {
                    items(availableQualities) { opt ->
                        Text(
                            opt.label,
                            color = if (opt.label == currentQualityLabel) AccentRed else Color.White,
                            fontWeight = if (opt.label == currentQualityLabel) FontWeight.Bold else FontWeight.Normal,
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable {
                                    val pos = BgPlaybackService.player?.currentPosition ?: 0L
                                    startOptionInPlayer(opt, pos)
                                    showQualityDialog = false
                                }
                                .padding(vertical = 12.dp, horizontal = 8.dp)
                        )
                    }
                }
            },
            confirmButton = { TextButton(onClick = { showQualityDialog = false }) { Text("Close", color = AccentRed) } }
        )
    }

    @Composable
    private fun SpeedPickerDialog() {
        val speeds = listOf(0.5f, 0.75f, 1.0f, 1.25f, 1.5f, 2.0f)
        AlertDialog(
            onDismissRequest = { showSpeedDialog = false },
            containerColor = SurfaceDark,
            title = { Text("Playback Speed", color = Color.White) },
            text = {
                Column {
                    speeds.forEach { sp ->
                        Text(
                            text = if (sp == 1.0f) "1.0x (Normal)" else "${sp}x",
                            color = if (sp == currentSpeed) AccentRed else Color.White,
                            fontWeight = if (sp == currentSpeed) FontWeight.Bold else FontWeight.Normal,
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable {
                                    currentSpeed = sp
                                    BgPlaybackService.player?.setPlaybackSpeed(sp)
                                    showSpeedDialog = false
                                }
                                .padding(vertical = 11.dp, horizontal = 8.dp)
                        )
                    }
                }
            },
            confirmButton = { TextButton(onClick = { showSpeedDialog = false }) { Text("Close", color = AccentRed) } }
        )
    }

    @Composable
    private fun SubtitlePickerDialog() {
        AlertDialog(
            onDismissRequest = { showSubtitleDialog = false },
            containerColor = SurfaceDark,
            title = { Text("Subtitles (CC)", color = Color.White) },
            text = {
                LazyColumn {
                    item {
                        Text(
                            "Off",
                            color = if (currentSubtitleLabel == "Off") AccentRed else Color.White,
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable {
                                    currentSubtitleLabel = "Off"
                                    activeCues = emptyList()
                                    currentSubtitleText = ""
                                    showSubtitleDialog = false
                                }
                                .padding(vertical = 11.dp, horizontal = 8.dp)
                        )
                    }
                    items(availableSubtitles) { sub ->
                        Text(
                            sub.label,
                            color = if (sub.label == currentSubtitleLabel) AccentRed else Color.White,
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable {
                                    currentSubtitleLabel = sub.label
                                    executor.execute {
                                        val parsed = YouTubeBackend.fetchAndParseVtt(sub.url)
                                        runOnUiThread { activeCues = parsed }
                                    }
                                    showSubtitleDialog = false
                                }
                                .padding(vertical = 11.dp, horizontal = 8.dp)
                        )
                    }
                }
            },
            confirmButton = { TextButton(onClick = { showSubtitleDialog = false }) { Text("Close", color = AccentRed) } }
        )
    }

    @Composable
    private fun DownloadPickerDialog() {
        AlertDialog(
            onDismissRequest = { showDownloadDialog = false },
            containerColor = SurfaceDark,
            title = { Text("Download Offline", color = Color.White) },
            text = {
                Column {
                    if (bestHdVideoUrl != null && bestAudioM4aUrl != null) {
                        Text(
                            "HD Video ($bestHdLabel MP4) — MediaMuxer",
                            color = AccentGreen,
                            fontWeight = FontWeight.Bold,
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable {
                                    showDownloadDialog = false
                                    executor.execute {
                                        YouTubeBackend.downloadAndMuxHdMp4(
                                            context = applicationContext,
                                            videoUrl = bestHdVideoUrl!!,
                                            audioUrl = bestAudioM4aUrl!!,
                                            title = activeTitle,
                                            onProgress = { msg -> runOnUiThread { downloadStatusText = msg } },
                                            onDone = { fileName ->
                                                runOnUiThread {
                                                    Toast.makeText(this@MainActivity, "Saved HD: Downloads/$fileName", Toast.LENGTH_LONG).show()
                                                }
                                            },
                                            onError = { err ->
                                                runOnUiThread {
                                                    Toast.makeText(this@MainActivity, "HD Error: $err", Toast.LENGTH_LONG).show()
                                                }
                                            }
                                        )
                                    }
                                }
                                .padding(vertical = 12.dp, horizontal = 8.dp)
                        )
                    }
                    if (bestMuxedUrl != null) {
                        Text(
                            "Standard Video (360p MP4)",
                            color = Color.White,
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable {
                                    showDownloadDialog = false
                                    try {
                                        val name = YouTubeBackend.startSystemDownload(this@MainActivity, bestMuxedUrl!!, activeTitle, "mp4")
                                        Toast.makeText(this@MainActivity, "Downloading Downloads/$name", Toast.LENGTH_LONG).show()
                                    } catch (e: Exception) {
                                        Toast.makeText(this@MainActivity, "Error: ${e.localizedMessage}", Toast.LENGTH_SHORT).show()
                                    }
                                }
                                .padding(vertical = 12.dp, horizontal = 8.dp)
                        )
                    }
                    if (bestAudioM4aUrl != null) {
                        Text(
                            "Audio Only (M4A)",
                            color = Color.White,
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable {
                                    showDownloadDialog = false
                                    try {
                                        val name = YouTubeBackend.startSystemDownload(this@MainActivity, bestAudioM4aUrl!!, activeTitle, "m4a")
                                        Toast.makeText(this@MainActivity, "Downloading Downloads/$name", Toast.LENGTH_LONG).show()
                                    } catch (e: Exception) {
                                        Toast.makeText(this@MainActivity, "Error: ${e.localizedMessage}", Toast.LENGTH_SHORT).show()
                                    }
                                }
                                .padding(vertical = 12.dp, horizontal = 8.dp)
                        )
                    }
                }
            },
            confirmButton = { TextButton(onClick = { showDownloadDialog = false }) { Text("Cancel", color = AccentRed) } }
        )
    }

    private fun loadFeedForCategory(category: String) {
        val query = when (category) {
            "Popular" -> "trending music tech 2026"
            "Bangla" -> "bangla natok music tech"
            else -> "$category latest videos"
        }
        isLoadingFeed = true
        feedError = null
        executor.execute {
            try {
                val items = YouTubeBackend.searchVideos(query)
                runOnUiThread {
                    homeVideos = items
                    isLoadingFeed = false
                }
            } catch (e: Exception) {
                runOnUiThread {
                    feedError = "ফিড লোড হয়নি: ${e.localizedMessage}"
                    isLoadingFeed = false
                }
            }
        }
    }

    private fun performSearch(query: String) {
        isLoadingFeed = true
        feedError = null
        executor.execute {
            try {
                val items = YouTubeBackend.searchVideos(query)
                runOnUiThread {
                    searchVideos = items
                    isLoadingFeed = false
                }
            } catch (e: Exception) {
                runOnUiThread {
                    feedError = "Search error: ${e.localizedMessage}"
                    isLoadingFeed = false
                }
            }
        }
    }

    private fun playVideoItem(item: VideoItemUi) {
        playYouTubeById(item.id, item.title, item.channelName, item.avatarUrl)
    }

    private fun playYouTubeById(
        videoId: String,
        previewTitle: String,
        previewChannel: String,
        previewAvatar: String?
    ) {
        activeVideoId = videoId
        isPlayerVisible = true
        isLoadingStream = true
        activeTitle = previewTitle
        activeChannel = previewChannel
        activeAvatarUrl = previewAvatar
        activeViewsText = ""
        activeDescription = ""
        activeVotesText = null
        activeDislikesText = null
        sponsorCount = 0
        sponsorSegments = emptyList()
        lastSkippedEndMs = -1L
        availableSubtitles = emptyList()
        currentSubtitleLabel = "Off"
        activeCues = emptyList()
        currentSubtitleText = ""
        bestHdVideoUrl = null
        bestMuxedUrl = null
        bestAudioM4aUrl = null
        relatedVideos = emptyList()

        executor.execute {
            try {
                val bundle = YouTubeBackend.loadStreamBundle(videoId)
                val defaultOption = bundle.qualities.firstOrNull { it.height == 720 }
                    ?: bundle.qualities.firstOrNull { it.height == 480 }
                    ?: bundle.qualities.firstOrNull()

                runOnUiThread {
                    isLoadingStream = false
                    availableQualities = bundle.qualities
                    availableSubtitles = bundle.subtitles
                    relatedVideos = bundle.relatedVideos
                    bestHdVideoUrl = bundle.bestHdVideoUrl
                    bestHdLabel = bundle.bestHdLabel
                    bestMuxedUrl = bundle.bestMuxedUrl
                    bestAudioM4aUrl = bundle.bestAudioM4aUrl

                    activeTitle = bundle.title
                    BgPlaybackService.currentTitle = bundle.title
                    activeChannel = bundle.channelName
                    activeAvatarUrl = bundle.avatarUrl ?: previewAvatar
                    activeViewsText = bundle.viewsText
                    activeDescription = bundle.description

                    saveToHistory(bundle.historyItem)

                    if (defaultOption != null) {
                        startOptionInPlayer(defaultOption, 0L)
                    } else {
                        Toast.makeText(this, "Stream পাওয়া যায়নি", Toast.LENGTH_SHORT).show()
                    }
                }
            } catch (e: Exception) {
                runOnUiThread {
                    isLoadingStream = false
                    Toast.makeText(this, "Error: ${e.localizedMessage}", Toast.LENGTH_SHORT).show()
                }
            }
        }

        executor.execute {
            val segs = YouTubeBackend.fetchSponsorSegments(videoId)
            sponsorSegments = segs
            val votes = YouTubeBackend.fetchDislikeStats(videoId)
            runOnUiThread {
                sponsorCount = segs.size
                if (votes != null) {
                    activeVotesText = votes.first
                    activeDislikesText = votes.second
                }
            }
        }
    }

    @androidx.annotation.OptIn(UnstableApi::class)
    private fun startOptionInPlayer(option: VideoQualityOption, resumePositionMs: Long) {
        val dataSourceFactory = DefaultHttpDataSource.Factory().setUserAgent(YouTubeBackend.USER_AGENT)

        val mediaSource: MediaSource = if (option.isHls) {
            HlsMediaSource.Factory(dataSourceFactory).createMediaSource(MediaItem.fromUri(option.videoUrl))
        } else if (option.audioUrl != null) {
            val vSrc = ProgressiveMediaSource.Factory(dataSourceFactory).createMediaSource(MediaItem.fromUri(option.videoUrl))
            val aSrc = ProgressiveMediaSource.Factory(dataSourceFactory).createMediaSource(MediaItem.fromUri(option.audioUrl))
            MergingMediaSource(vSrc, aSrc)
        } else {
            ProgressiveMediaSource.Factory(dataSourceFactory).createMediaSource(MediaItem.fromUri(option.videoUrl))
        }

        currentQualityLabel = option.label
        val p = BgPlaybackService.ensurePlayer(applicationContext)
        playerViewRef?.player = p
        p.trackSelectionParameters = p.trackSelectionParameters.buildUpon()
            .setTrackTypeDisabled(C.TRACK_TYPE_VIDEO, false)
            .build()
        p.repeatMode = if (isLooping) Player.REPEAT_MODE_ONE else Player.REPEAT_MODE_OFF
        p.setMediaSource(mediaSource)
        p.prepare()
        if (resumePositionMs > 0L) p.seekTo(resumePositionMs)
        p.setPlaybackSpeed(currentSpeed)
        p.playWhenReady = true

        if (bgPlayEnabled) startBgPlaybackService()
    }

    private fun saveToHistory(item: VideoItemUi) {
        val updated = listOf(item) + historyVideos.filter { it.id != item.id }.take(29)
        historyVideos = updated
        val arr = JSONArray()
        for (v in updated) {
            arr.put(JSONObject().apply {
                put("id", v.id)
                put("url", v.url)
                put("title", v.title)
                put("channel", v.channelName)
                put("thumb", v.thumbnailUrl)
                put("avatar", v.avatarUrl ?: "")
                put("dur", v.durationSec)
                put("views", v.viewCount)
            })
        }
        getSharedPreferences("yt_history_v2", Context.MODE_PRIVATE).edit().putString("items", arr.toString()).apply()
    }

    private fun loadHistoryFromPrefs() {
        val prefs = getSharedPreferences("yt_history_v2", Context.MODE_PRIVATE)
        val arr = try { JSONArray(prefs.getString("items", "[]")) } catch (_: Exception) { JSONArray() }
        val list = mutableListOf<VideoItemUi>()
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            list.add(
                VideoItemUi(
                    id = o.getString("id"),
                    url = o.getString("url"),
                    title = o.getString("title"),
                    channelName = o.optString("channel", "YouTube"),
                    thumbnailUrl = o.optString("thumb", ""),
                    avatarUrl = o.optString("avatar", "").takeIf { it.isNotBlank() },
                    durationSec = o.optLong("dur", 0),
                    viewCount = o.optLong("views", 0),
                    uploadDate = null
                )
            )
        }
        historyVideos = list
    }

    private fun clearHistory() {
        historyVideos = emptyList()
        getSharedPreferences("yt_history_v2", Context.MODE_PRIVATE).edit().remove("items").apply()
    }

    private fun startBgPlaybackService() {
        val intent = Intent(this, BgPlaybackService::class.java).apply {
            action = BgPlaybackService.ACTION_START_OR_UPDATE
            putExtra(BgPlaybackService.EXTRA_TITLE, activeTitle)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(intent)
        } else {
            startService(intent)
        }
    }

    private fun enterPipMode() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            enterPictureInPictureMode(PictureInPictureParams.Builder().setAspectRatio(Rational(16, 9)).build())
        } else {
            Toast.makeText(this, "PiP requires Android 8.0+", Toast.LENGTH_SHORT).show()
        }
    }

    private fun toggleFullscreen(enter: Boolean) {
        isFullscreen = enter
        if (enter) {
            requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
            setSystemUiHidden(true)
        } else {
            if (isTouchLocked) setPlayerTouchLocked(false)
            requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
            setSystemUiHidden(false)
        }
    }

    @Suppress("DEPRECATION")
    private fun setSystemUiHidden(hide: Boolean) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val controller = window.insetsController ?: return
            if (hide) {
                controller.hide(WindowInsets.Type.statusBars() or WindowInsets.Type.navigationBars())
                controller.systemBarsBehavior = WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
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
        BgPlaybackService.player?.clearMediaItems()
        stopService(Intent(this, BgPlaybackService::class.java))
        isPlayerVisible = false
    }

    override fun onStart() {
        super.onStart()
        val p = BgPlaybackService.player
        if (p != null) {
            p.trackSelectionParameters = p.trackSelectionParameters.buildUpon()
                .setTrackTypeDisabled(C.TRACK_TYPE_VIDEO, false)
                .build()
            playerViewRef?.player = p
        }
    }

    override fun onStop() {
        super.onStop()
        val isPip = Build.VERSION.SDK_INT >= Build.VERSION_CODES.N && isInPictureInPictureMode
        if (isPip) return

        val p = BgPlaybackService.player
        if (bgPlayEnabled && p != null && p.isPlaying) {
            playerViewRef?.player = null
            p.trackSelectionParameters = p.trackSelectionParameters.buildUpon()
                .setTrackTypeDisabled(C.TRACK_TYPE_VIDEO, true)
                .build()
        } else {
            p?.pause()
            stopService(Intent(this, BgPlaybackService::class.java))
        }
    }

    override fun onDestroy() {
        playerViewRef?.player = null
        playerViewRef = null
        BgPlaybackService.onCloseFromNotification = null
        BgPlaybackService.onPlaybackEnded = null
        BgPlaybackService.onIsPlayingChanged = null
        if (!bgPlayEnabled || BgPlaybackService.player?.isPlaying != true) {
            stopService(Intent(this, BgPlaybackService::class.java))
        }
        executor.shutdownNow()
        super.onDestroy()
    }
}