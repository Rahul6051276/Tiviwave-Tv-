package com.example.ui

import android.view.KeyEvent as AndroidKeyEvent
import androidx.activity.compose.BackHandler
import androidx.annotation.OptIn
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.AspectRatio
import androidx.compose.material.icons.filled.Audiotrack
import androidx.compose.material.icons.filled.ClosedCaption
import androidx.compose.material.icons.filled.FastForward
import androidx.compose.material.icons.filled.FastRewind
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.StarBorder
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.C
import androidx.media3.common.MediaItem as Media3Item
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.TrackSelectionOverride
import androidx.media3.common.Tracks
import androidx.media3.common.VideoSize
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.okhttp.OkHttpDataSource
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import com.example.models.AspectRatioMode
import com.example.models.ContentType
import com.example.models.EpgProgram
import com.example.models.MediaItem
import com.example.models.ResolvedStream
import com.example.models.StreamTechInfo
import com.example.models.TrackOption
import com.example.routing.RoutingManager
import com.example.ui.theme.BroadcastCyan
import com.example.ui.theme.BroadcastCyanDeep
import com.example.ui.theme.DividerSlate
import com.example.ui.theme.ElevatedSlate
import com.example.ui.theme.HighlightSlate
import com.example.ui.theme.LiveCrimson
import com.example.ui.theme.MidnightPanel
import com.example.ui.theme.ObsidianBase
import com.example.ui.theme.ObsidianVoid
import com.example.ui.theme.SignalAmber
import com.example.ui.theme.TextMuted
import com.example.ui.theme.TextPrimary
import com.example.ui.theme.TextSecondary
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private enum class TrackPickerMode { NONE, AUDIO, SUBTITLES }

/**
 * Full-Screen Hardware-Accelerated Media3 TV Player:
 * - Supports HLS, DASH, RTSP, TS/Progressive over proxy-aware OkHttpDataSource
 * - Live TV: UP/DOWN zaps channels immediately with TiviMate bottom OSD banner;
 *   OK toggles transparent Channel & EPG overlay; RIGHT/MENU opens audio/subtitle/aspect controls
 * - VOD/Series: OK toggles play/pause, LEFT/RIGHT seeks -15s/+15s, auto-saves resume position
 * - Auto-Reconnect with exponential backoff & Stalker create_link refresh
 */
@OptIn(UnstableApi::class)
@Composable
fun TvPlayerScreen(
    item: MediaItem,
    resolvedStream: ResolvedStream?,
    isResolvingStream: Boolean,
    resolveError: String?,
    liveZapQueue: List<MediaItem>,
    epgByChannelId: Map<String, List<EpgProgram>>,
    onZapChannel: (Int) -> Unit,
    onSwitchLiveChannel: (MediaItem) -> Unit,
    onRefreshStream: () -> Unit,
    onToggleFavorite: (MediaItem) -> Unit,
    onSaveResumePosition: (String, Long, Long) -> Unit,
    onExitPlayer: () -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val rootFocusRequester = remember { FocusRequester() }

    val isLive = item.type == ContentType.LIVE
    var showOsdBanner by remember { mutableStateOf(true) }
    var showChannelOverlay by remember { mutableStateOf(false) }
    var showSettingsBar by remember { mutableStateOf(false) }
    var trackPickerMode by remember { mutableStateOf(TrackPickerMode.NONE) }
    var osdTriggerToken by remember { mutableIntStateOf(0) }

    var isPlaying by remember { mutableStateOf(true) }
    var isBuffering by remember { mutableStateOf(true) }
    var playbackError by remember(item.id, resolvedStream?.url) { mutableStateOf<String?>(null) }
    var reconnectAttempt by remember(item.id, resolvedStream?.url) { mutableIntStateOf(0) }
    var aspectMode by remember { mutableStateOf(AspectRatioMode.FIT) }
    var techInfo by remember(item.id) { mutableStateOf(StreamTechInfo()) }
    var audioTracks by remember(item.id) { mutableStateOf<List<TrackOption>>(emptyList()) }
    var subtitleTracks by remember(item.id) { mutableStateOf<List<TrackOption>>(emptyList()) }
    var currentPosMs by remember(item.id) { mutableLongStateOf(item.resumePositionMs) }
    var durationMs by remember(item.id) { mutableLongStateOf(item.durationMs) }

    BackHandler {
        when {
            trackPickerMode != TrackPickerMode.NONE -> trackPickerMode = TrackPickerMode.NONE
            showSettingsBar -> showSettingsBar = false
            showChannelOverlay -> showChannelOverlay = false
            else -> onExitPlayer()
        }
    }

    // Re-show OSD whenever channel changes
    LaunchedEffect(item.id) {
        showOsdBanner = true
        osdTriggerToken++
    }

    // Auto-hide OSD after 5 seconds when no menu overlay is open
    LaunchedEffect(osdTriggerToken, showChannelOverlay, showSettingsBar, trackPickerMode) {
        if (showOsdBanner && !showChannelOverlay && !showSettingsBar && trackPickerMode == TrackPickerMode.NONE) {
            delay(5000L)
            showOsdBanner = false
        }
    }

    // Request focus back to player canvas when overlays close so D-Pad zapping works seamlessly
    LaunchedEffect(showChannelOverlay, showSettingsBar, trackPickerMode) {
        if (!showChannelOverlay && !showSettingsBar && trackPickerMode == TrackPickerMode.NONE) {
            delay(80L)
            runCatching { rootFocusRequester.requestFocus() }
        }
    }

    // Build Media3 ExoPlayer with fast startup LoadControl and proxy-aware OkHttpDataSource
    val exoPlayer = remember(resolvedStream?.url, resolvedStream?.headers) {
        val headers = resolvedStream?.headers.orEmpty()
        val httpClient = RoutingManager.buildHttpClient(customHeaders = headers)
        val okHttpFactory = OkHttpDataSource.Factory(httpClient)
        val loadControl = DefaultLoadControl.Builder()
            .setBufferDurationsMs(
                15_000,
                50_000,
                1_200,
                2_500
            )
            .build()

        ExoPlayer.Builder(context)
            .setMediaSourceFactory(DefaultMediaSourceFactory(okHttpFactory))
            .setLoadControl(loadControl)
            .build()
            .apply {
                playWhenReady = true
                if (!resolvedStream?.url.isNullOrBlank()) {
                    setMediaItem(Media3Item.fromUri(resolvedStream!!.url))
                    if (!isLive && item.resumePositionMs > 5_000L) {
                        seekTo(item.resumePositionMs)
                    }
                    prepare()
                }
            }
    }

    // Track Media3 state, video resolution/codecs, tracks, and Auto-Reconnect
    DisposableEffect(exoPlayer, item.id) {
        val listener = object : Player.Listener {
            override fun onIsPlayingChanged(playing: Boolean) {
                isPlaying = playing
            }

            override fun onPlaybackStateChanged(playbackState: Int) {
                isBuffering = playbackState == Player.STATE_BUFFERING
                if (playbackState == Player.STATE_READY) {
                    playbackError = null
                    reconnectAttempt = 0
                }
            }

            override fun onVideoSizeChanged(videoSize: VideoSize) {
                if (videoSize.width > 0 && videoSize.height > 0) {
                    val resLabel = when {
                        videoSize.height >= 2160 -> "4K UHD (${videoSize.width}×${videoSize.height})"
                        videoSize.height >= 1080 -> "1080p FHD"
                        videoSize.height >= 720 -> "720p HD"
                        else -> "${videoSize.width}×${videoSize.height}"
                    }
                    techInfo = techInfo.copy(resolution = resLabel)
                }
            }

            override fun onTracksChanged(tracks: Tracks) {
                val foundAudio = mutableListOf<TrackOption>()
                val foundSubs = mutableListOf<TrackOption>()
                var vCodec = techInfo.videoCodec
                var aCodec = techInfo.audioCodec
                var bitrate = techInfo.bitrateKbps
                var fpsStr = techInfo.frameRate

                tracks.groups.forEachIndexed { gIdx, group ->
                    for (tIdx in 0 until group.length) {
                        val format = group.getTrackFormat(tIdx)
                        val selected = group.isTrackSelected(tIdx)
                        when (group.type) {
                            C.TRACK_TYPE_VIDEO -> if (selected) {
                                vCodec = simplifyCodec(format.sampleMimeType ?: format.codecs.orEmpty())
                                if (format.bitrate > 0) bitrate = format.bitrate / 1000
                                if (format.frameRate > 0f) fpsStr = "${format.frameRate.toInt()}fps"
                            }

                            C.TRACK_TYPE_AUDIO -> {
                                val lang = format.language?.uppercase() ?: "UND"
                                val ch = if (format.channelCount > 0) "${format.channelCount}ch" else ""
                                val label = format.label ?: "$lang ${simplifyCodec(format.sampleMimeType.orEmpty())} $ch".trim()
                                foundAudio += TrackOption(gIdx, tIdx, label, lang, selected)
                                if (selected) {
                                    aCodec = simplifyCodec(format.sampleMimeType ?: format.codecs.orEmpty())
                                }
                            }

                            C.TRACK_TYPE_TEXT -> {
                                val lang = format.language?.uppercase() ?: "SUB"
                                val label = format.label ?: "Subtitle ($lang)"
                                foundSubs += TrackOption(gIdx, tIdx, label, lang, selected)
                            }
                        }
                    }
                }
                audioTracks = foundAudio
                subtitleTracks = foundSubs
                techInfo = techInfo.copy(
                    videoCodec = vCodec,
                    audioCodec = aCodec,
                    bitrateKbps = bitrate,
                    frameRate = fpsStr
                )
            }

            override fun onPlayerError(error: PlaybackException) {
                playbackError = error.errorCodeName
                if (reconnectAttempt < 5) {
                    val nextTry = reconnectAttempt + 1
                    reconnectAttempt = nextTry
                    scope.launch {
                        delay(1500L * nextTry)
                        if (nextTry == 2 || nextTry == 4) {
                            onRefreshStream()
                        } else {
                            exoPlayer.prepare()
                            exoPlayer.playWhenReady = true
                        }
                    }
                }
            }
        }

        exoPlayer.addListener(listener)
        onDispose {
            if (!isLive) {
                val pos = exoPlayer.currentPosition
                val dur = exoPlayer.duration.coerceAtLeast(0L)
                if (pos > 5_000L) {
                    onSaveResumePosition(item.id, pos, dur)
                }
            }
            exoPlayer.removeListener(listener)
            exoPlayer.release()
        }
    }

    // Periodic progress polling & VOD resume autosave
    LaunchedEffect(exoPlayer, item.id) {
        var tick = 0
        while (true) {
            delay(1000L)
            currentPosMs = exoPlayer.currentPosition.coerceAtLeast(0L)
            val d = exoPlayer.duration
            if (d > 0L && d != C.TIME_UNSET) {
                durationMs = d
            }
            tick++
            if (!isLive && tick % 10 == 0 && currentPosMs > 5_000L) {
                onSaveResumePosition(item.id, currentPosMs, durationMs)
            }
        }
    }

    val activeEpgKey = item.epgChannelId.ifBlank { item.id }
    val channelEpg = epgByChannelId[activeEpgKey].orEmpty()
    val nowMs = System.currentTimeMillis()
    val currentProgram = channelEpg.firstOrNull { it.isCurrentAt(nowMs) } ?: channelEpg.firstOrNull()
    val nextProgram = channelEpg.firstOrNull { it.startTimeMs > (currentProgram?.startTimeMs ?: nowMs) }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black)
            .focusRequester(rootFocusRequester)
            .focusable()
            .onPreviewKeyEvent { keyEvent ->
                if (keyEvent.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                val code = keyEvent.nativeKeyEvent.keyCode

                // When an overlay is open, let Compose focus navigation operate naturally inside the overlay
                if (showChannelOverlay || showSettingsBar || trackPickerMode != TrackPickerMode.NONE) {
                    return@onPreviewKeyEvent false
                }

                when (code) {
                    AndroidKeyEvent.KEYCODE_DPAD_UP,
                    AndroidKeyEvent.KEYCODE_CHANNEL_UP -> {
                        if (isLive) {
                            onZapChannel(-1)
                        } else {
                            showOsdBanner = true
                            showSettingsBar = true
                            osdTriggerToken++
                        }
                        true
                    }

                    AndroidKeyEvent.KEYCODE_DPAD_DOWN,
                    AndroidKeyEvent.KEYCODE_CHANNEL_DOWN -> {
                        if (isLive) {
                            onZapChannel(+1)
                        } else {
                            showOsdBanner = true
                            showSettingsBar = true
                            osdTriggerToken++
                        }
                        true
                    }

                    AndroidKeyEvent.KEYCODE_DPAD_CENTER,
                    AndroidKeyEvent.KEYCODE_ENTER,
                    AndroidKeyEvent.KEYCODE_NUMPAD_ENTER -> {
                        if (isLive) {
                            showChannelOverlay = !showChannelOverlay
                            showOsdBanner = true
                            osdTriggerToken++
                        } else {
                            if (exoPlayer.isPlaying) exoPlayer.pause() else exoPlayer.play()
                            showOsdBanner = true
                            osdTriggerToken++
                        }
                        true
                    }

                    AndroidKeyEvent.KEYCODE_MEDIA_PLAY_PAUSE -> {
                        if (exoPlayer.isPlaying) exoPlayer.pause() else exoPlayer.play()
                        showOsdBanner = true
                        osdTriggerToken++
                        true
                    }

                    AndroidKeyEvent.KEYCODE_DPAD_LEFT -> {
                        if (!isLive) {
                            exoPlayer.seekTo((exoPlayer.currentPosition - 15_000L).coerceAtLeast(0L))
                            showOsdBanner = true
                            osdTriggerToken++
                        } else {
                            showChannelOverlay = true
                        }
                        true
                    }

                    AndroidKeyEvent.KEYCODE_DPAD_RIGHT -> {
                        if (!isLive) {
                            val maxPos = if (durationMs > 0L) durationMs else Long.MAX_VALUE
                            exoPlayer.seekTo((exoPlayer.currentPosition + 15_000L).coerceAtMost(maxPos))
                            showOsdBanner = true
                            osdTriggerToken++
                        } else {
                            showSettingsBar = true
                            showOsdBanner = true
                        }
                        true
                    }

                    AndroidKeyEvent.KEYCODE_MENU,
                    AndroidKeyEvent.KEYCODE_INFO -> {
                        showSettingsBar = !showSettingsBar
                        showOsdBanner = true
                        osdTriggerToken++
                        true
                    }

                    else -> false
                }
            }
            .testTag("tv_player_root")
    ) {
        // Hardware-Accelerated Media3 Video Surface
        AndroidView(
            factory = { ctx ->
                PlayerView(ctx).apply {
                    useController = false
                    keepScreenOn = true
                    player = exoPlayer
                    resizeMode = mapAspectMode(aspectMode)
                }
            },
            update = { view ->
                view.player = exoPlayer
                view.resizeMode = mapAspectMode(aspectMode)
            },
            modifier = Modifier.fillMaxSize()
        )

        // Buffering / Resolving / Reconnecting Center Pill
        if (isResolvingStream || isBuffering || playbackError != null || resolveError != null) {
            Box(
                modifier = Modifier
                    .align(Alignment.Center)
                    .clip(RoundedCornerShape(14.dp))
                    .background(ObsidianVoid.copy(alpha = 0.88f))
                    .border(1.5.dp, DividerSlate, RoundedCornerShape(14.dp))
                    .padding(horizontal = 24.dp, vertical = 16.dp)
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(14.dp)
                ) {
                    if (resolveError == null && (isResolvingStream || isBuffering || reconnectAttempt < 5)) {
                        CircularProgressIndicator(
                            color = BroadcastCyan,
                            strokeWidth = 3.dp,
                            modifier = Modifier.size(26.dp)
                        )
                    }
                    Column {
                        val statusText = when {
                            isResolvingStream -> "Resolving secure stream link..."
                            resolveError != null -> "Stream Error: $resolveError"
                            playbackError != null && reconnectAttempt <= 5 -> "Reconnecting stream (Attempt $reconnectAttempt/5)..."
                            playbackError != null -> "Playback Failed ($playbackError)"
                            else -> "Buffering stream..."
                        }
                        Text(statusText, style = MaterialTheme.typography.titleMedium, color = TextPrimary)
                        if (resolveError != null || (playbackError != null && reconnectAttempt >= 5)) {
                            Spacer(Modifier.height(8.dp))
                            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                                TvActionButton(
                                    text = "Retry Stream",
                                    icon = Icons.Default.Refresh,
                                    isPrimary = true,
                                    onClick = {
                                        reconnectAttempt = 0
                                        playbackError = null
                                        onRefreshStream()
                                    }
                                )
                                TvActionButton(
                                    text = "Exit Player",
                                    icon = Icons.AutoMirrored.Filled.ArrowBack,
                                    onClick = onExitPlayer
                                )
                            }
                        }
                    }
                }
            }
        }

        // Top-Right Quick Status Badge (Aspect Ratio + Remote Hints)
        AnimatedVisibility(
            visible = showOsdBanner || showSettingsBar,
            enter = fadeIn(),
            exit = fadeOut(),
            modifier = Modifier
                .align(Alignment.TopEnd)
                .padding(24.dp)
        ) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TvBadge(text = "ASPECT: ${aspectMode.label}", containerColor = ObsidianVoid.copy(alpha = 0.85f))
                if (isLive) {
                    TvBadge(
                        text = "▲▼ ZAP • OK GUIDE • ▶ TRACKS",
                        containerColor = ObsidianVoid.copy(alpha = 0.85f),
                        contentColor = TextSecondary
                    )
                } else {
                    TvBadge(
                        text = "◀▶ SEEK 15s • OK PLAY/PAUSE • ▲▼ TRACKS",
                        containerColor = ObsidianVoid.copy(alpha = 0.85f),
                        contentColor = TextSecondary
                    )
                }
            }
        }

        // ---------------------------------------------------------------------
        // TRANSPARENT LIVE TV CHANNEL & EPG OVERLAY DRAWER (TOGGLED BY OK)
        // ---------------------------------------------------------------------
        AnimatedVisibility(
            visible = isLive && showChannelOverlay,
            enter = slideInHorizontally { -it } + fadeIn(),
            exit = slideOutHorizontally { -it } + fadeOut(),
            modifier = Modifier
                .align(Alignment.CenterStart)
                .fillMaxHeight()
                .width(420.dp)
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .background(ObsidianVoid.copy(alpha = 0.88f))
                    .border(1.dp, DividerSlate.copy(alpha = 0.7f))
                    .padding(18.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text("Live Channels Overlay", style = MaterialTheme.typography.titleLarge, color = TextPrimary)
                    TvActionButton(
                        text = "Close",
                        onClick = { showChannelOverlay = false },
                        testTag = "player_overlay_close_button"
                    )
                }
                Spacer(Modifier.height(12.dp))
                LazyColumn(
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                    contentPadding = PaddingValues(4.dp)
                ) {
                    items(liveZapQueue, key = { it.id }) { ch ->
                        val chEpgKey = ch.epgChannelId.ifBlank { ch.id }
                        val chNow = epgByChannelId[chEpgKey]?.firstOrNull { it.isCurrentAt(nowMs) }
                            ?: epgByChannelId[chEpgKey]?.firstOrNull()
                        val isCurrent = ch.id == item.id

                        TvFocusableSurface(
                            onClick = {
                                onSwitchLiveChannel(ch)
                                showChannelOverlay = false
                            },
                            selected = isCurrent,
                            focusScale = 1.03f,
                            containerColor = MidnightPanel.copy(alpha = 0.85f),
                            selectedContainerColor = BroadcastCyanDeep.copy(alpha = 0.55f),
                            focusedContainerColor = HighlightSlate,
                            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 10.dp),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(
                                    text = if (ch.channelNumber > 0) ch.channelNumber.toString().padStart(3, '0') else "---",
                                    style = MaterialTheme.typography.labelMedium,
                                    color = BroadcastCyan,
                                    modifier = Modifier.width(34.dp)
                                )
                                ChannelLogoThumbnail(
                                    title = ch.title,
                                    logoUrl = ch.posterUrl,
                                    modifier = Modifier.size(38.dp)
                                )
                                Spacer(Modifier.width(10.dp))
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        text = ch.title,
                                        style = MaterialTheme.typography.titleSmall,
                                        color = TextPrimary,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                    Text(
                                        text = chNow?.title ?: "Live Stream",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = TextMuted,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }

        // ---------------------------------------------------------------------
        // BOTTOM TIVIMATE OSD BANNER + PLAYER CONTROLS BAR
        // ---------------------------------------------------------------------
        AnimatedVisibility(
            visible = showOsdBanner || showSettingsBar,
            enter = slideInVertically { it } + fadeIn(),
            exit = slideOutVertically { it } + fadeOut(),
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(
                        Brush.verticalGradient(
                            listOf(
                                Color.Transparent,
                                ObsidianVoid.copy(alpha = 0.90f),
                                ObsidianVoid.copy(alpha = 0.97f)
                            )
                        )
                    )
                    .padding(horizontal = 32.dp, vertical = 22.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                // Main Channel / Media Banner Row
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    if (isLive) {
                        ChannelLogoThumbnail(
                            title = item.title,
                            logoUrl = item.posterUrl,
                            modifier = Modifier.size(62.dp)
                        )
                        Spacer(Modifier.width(16.dp))
                    }

                    Column(modifier = Modifier.weight(1f)) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            if (isLive && item.channelNumber > 0) {
                                TvBadge(
                                    text = "#${item.channelNumber}",
                                    containerColor = BroadcastCyanDeep,
                                    contentColor = Color.White
                                )
                            }
                            if (isLive) {
                                Row(
                                    modifier = Modifier
                                        .clip(RoundedCornerShape(6.dp))
                                        .background(LiveCrimson)
                                        .padding(horizontal = 8.dp, vertical = 3.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Box(Modifier.size(6.dp).clip(CircleShape).background(Color.White))
                                    Spacer(Modifier.width(5.dp))
                                    Text("LIVE", style = MaterialTheme.typography.labelSmall, color = Color.White)
                                }
                            }
                            Text(
                                text = item.title,
                                style = MaterialTheme.typography.headlineMedium,
                                color = TextPrimary,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }

                        Spacer(Modifier.height(6.dp))

                        if (isLive) {
                            val timeFmt = remember { SimpleDateFormat("HH:mm", Locale.getDefault()) }
                            if (currentProgram != null) {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Text(
                                        text = "NOW: ${currentProgram.title} (${timeFmt.format(Date(currentProgram.startTimeMs))} - ${timeFmt.format(Date(currentProgram.endTimeMs))})",
                                        style = MaterialTheme.typography.titleSmall,
                                        color = BroadcastCyan,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                        modifier = Modifier.weight(1f)
                                    )
                                    if (nextProgram != null) {
                                        Spacer(Modifier.width(14.dp))
                                        Text(
                                            text = "NEXT: ${nextProgram.title}",
                                            style = MaterialTheme.typography.bodySmall,
                                            color = TextMuted,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis
                                        )
                                    }
                                }
                                Spacer(Modifier.height(6.dp))
                                LinearProgressIndicator(
                                    progress = { currentProgram.progressAt(nowMs) },
                                    color = BroadcastCyan,
                                    trackColor = ElevatedSlate,
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .height(4.dp)
                                        .clip(RoundedCornerShape(2.dp))
                                )
                            } else {
                                Text(
                                    text = "Live Broadcast Stream • Use ▲/▼ to zap channels",
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = TextSecondary
                                )
                            }
                        } else {
                            // VOD / Series Timeline Bar
                            val progress = if (durationMs > 0L) {
                                (currentPosMs.toFloat() / durationMs.toFloat()).coerceIn(0f, 1f)
                            } else 0f
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Text(formatDurationMs(currentPosMs), style = MaterialTheme.typography.labelMedium, color = BroadcastCyan)
                                Text(formatDurationMs(durationMs), style = MaterialTheme.typography.labelMedium, color = TextMuted)
                            }
                            Spacer(Modifier.height(4.dp))
                            LinearProgressIndicator(
                                progress = { progress },
                                color = BroadcastCyan,
                                trackColor = ElevatedSlate,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(5.dp)
                                    .clip(RoundedCornerShape(3.dp))
                            )
                        }
                    }

                    Spacer(Modifier.width(18.dp))

                    // Technical Stream Telemetry Badges (Resolution / Video Codec / Audio Codec / Bitrate)
                    Column(
                        horizontalAlignment = Alignment.End,
                        verticalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            if (techInfo.resolution.isNotBlank()) TvBadge(text = techInfo.resolution)
                            if (techInfo.videoCodec.isNotBlank()) TvBadge(text = techInfo.videoCodec, contentColor = SignalAmber)
                        }
                        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            if (techInfo.audioCodec.isNotBlank()) TvBadge(text = techInfo.audioCodec, contentColor = TextSecondary)
                            if (techInfo.bitrateKbps > 0) TvBadge(text = "${techInfo.bitrateKbps} kbps", contentColor = TextSecondary)
                            if (techInfo.frameRate.isNotBlank()) TvBadge(text = techInfo.frameRate, contentColor = TextSecondary)
                        }
                    }
                }

                // Interactive D-Pad Player Settings & Transport Controls Bar
                if (showSettingsBar) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        TvActionButton(
                            text = if (isPlaying) "Pause" else "Play",
                            icon = if (isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow,
                            isPrimary = true,
                            onClick = { if (exoPlayer.isPlaying) exoPlayer.pause() else exoPlayer.play() },
                            testTag = "player_play_pause_button"
                        )
                        if (!isLive) {
                            TvActionButton(
                                text = "-15s",
                                icon = Icons.Default.FastRewind,
                                onClick = { exoPlayer.seekTo((exoPlayer.currentPosition - 15_000L).coerceAtLeast(0L)) },
                                testTag = "player_rewind_button"
                            )
                            TvActionButton(
                                text = "+15s",
                                icon = Icons.Default.FastForward,
                                onClick = { exoPlayer.seekTo(exoPlayer.currentPosition + 15_000L) },
                                testTag = "player_forward_button"
                            )
                        }
                        TvActionButton(
                            text = "Audio (${audioTracks.size})",
                            icon = Icons.Default.Audiotrack,
                            onClick = { trackPickerMode = TrackPickerMode.AUDIO },
                            testTag = "player_audio_tracks_button"
                        )
                        TvActionButton(
                            text = "Subtitles (${subtitleTracks.size})",
                            icon = Icons.Default.ClosedCaption,
                            onClick = { trackPickerMode = TrackPickerMode.SUBTITLES },
                            testTag = "player_subtitles_button"
                        )
                        TvActionButton(
                            text = "Aspect: ${aspectMode.label}",
                            icon = Icons.Default.AspectRatio,
                            onClick = {
                                val modes = AspectRatioMode.entries
                                aspectMode = modes[(modes.indexOf(aspectMode) + 1) % modes.size]
                            },
                            testTag = "player_aspect_button"
                        )
                        TvActionButton(
                            text = if (item.isFavorite) "Saved" else "Fav",
                            icon = if (item.isFavorite) Icons.Default.Star else Icons.Default.StarBorder,
                            onClick = { onToggleFavorite(item) },
                            testTag = "player_favorite_button"
                        )
                        TvActionButton(
                            text = "Reconnect",
                            icon = Icons.Default.Refresh,
                            onClick = { onRefreshStream() },
                            testTag = "player_reconnect_button"
                        )
                        TvActionButton(
                            text = "Close",
                            icon = Icons.Default.Tune,
                            onClick = { showSettingsBar = false },
                            testTag = "player_hide_controls_button"
                        )
                    }
                }
            }
        }

        // ---------------------------------------------------------------------
        // AUDIO / SUBTITLE TRACK SWITCHER MODAL OVERLAY
        // ---------------------------------------------------------------------
        if (trackPickerMode != TrackPickerMode.NONE) {
            val isAudio = trackPickerMode == TrackPickerMode.AUDIO
            val options = if (isAudio) audioTracks else subtitleTracks

            Box(
                modifier = Modifier
                    .align(Alignment.CenterEnd)
                    .fillMaxHeight()
                    .width(380.dp)
                    .background(ObsidianVoid.copy(alpha = 0.94f))
                    .border(1.5.dp, BroadcastCyan.copy(alpha = 0.6f))
                    .padding(20.dp)
            ) {
                Column(modifier = Modifier.fillMaxSize()) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = if (isAudio) "Audio Tracks" else "Subtitle Tracks",
                            style = MaterialTheme.typography.titleLarge,
                            color = TextPrimary
                        )
                        TvActionButton(text = "Done", onClick = { trackPickerMode = TrackPickerMode.NONE })
                    }
                    Spacer(Modifier.height(14.dp))

                    LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        if (!isAudio) {
                            item(key = "sub_off") {
                                TvCheckboxRow(
                                    title = "Subtitles Off",
                                    checked = subtitleTracks.none { it.isSelected },
                                    onToggle = {
                                        exoPlayer.trackSelectionParameters = exoPlayer.trackSelectionParameters
                                            .buildUpon()
                                            .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, true)
                                            .build()
                                        trackPickerMode = TrackPickerMode.NONE
                                    }
                                )
                            }
                        }

                        if (options.isEmpty()) {
                            item(key = "no_tracks") {
                                Text(
                                    text = if (isAudio) "Using default audio track." else "No embedded subtitle tracks detected.",
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = TextMuted,
                                    modifier = Modifier.padding(12.dp)
                                )
                            }
                        } else {
                            items(options, key = { "${it.groupIndex}_${it.trackIndex}" }) { track ->
                                TvCheckboxRow(
                                    title = track.label,
                                    subtitle = track.language,
                                    checked = track.isSelected,
                                    onToggle = {
                                        val tracksGroups = exoPlayer.currentTracks.groups
                                        val mediaTrackGroup = tracksGroups.getOrNull(track.groupIndex)?.mediaTrackGroup
                                        if (mediaTrackGroup != null) {
                                            exoPlayer.trackSelectionParameters = exoPlayer.trackSelectionParameters
                                                .buildUpon()
                                                .setTrackTypeDisabled(
                                                    if (isAudio) C.TRACK_TYPE_AUDIO else C.TRACK_TYPE_TEXT,
                                                    false
                                                )
                                                .setOverrideForType(
                                                    TrackSelectionOverride(mediaTrackGroup, track.trackIndex)
                                                )
                                                .build()
                                        }
                                        trackPickerMode = TrackPickerMode.NONE
                                    }
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

@OptIn(UnstableApi::class)
private fun mapAspectMode(mode: AspectRatioMode): Int = when (mode) {
    AspectRatioMode.FIT -> AspectRatioFrameLayout.RESIZE_MODE_FIT
    AspectRatioMode.FILL -> AspectRatioFrameLayout.RESIZE_MODE_FILL
    AspectRatioMode.ZOOM -> AspectRatioFrameLayout.RESIZE_MODE_ZOOM
    AspectRatioMode.RATIO_16_9 -> AspectRatioFrameLayout.RESIZE_MODE_FIXED_WIDTH
}

private fun simplifyCodec(raw: String): String {
    val lower = raw.lowercase()
    return when {
        lower.contains("hevc") || lower.contains("h265") || lower.contains("hev1") -> "HEVC/H.265"
        lower.contains("avc") || lower.contains("h264") -> "H.264"
        lower.contains("av01") || lower.contains("av1") -> "AV1"
        lower.contains("vp9") -> "VP9"
        lower.contains("eac3") -> "Dolby Digital+"
        lower.contains("ac3") -> "AC3"
        lower.contains("mp4a") || lower.contains("aac") -> "AAC"
        lower.contains("opus") -> "OPUS"
        lower.contains("mpeg") -> "MPEG"
        else -> raw.substringAfter('/').uppercase().take(10)
    }
}
