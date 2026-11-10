package com.cadence.player.ui

import android.content.Context
import android.media.AudioManager
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.spring
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.basicMarquee
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.QueueMusic
import androidx.compose.material.icons.rounded.Cast
import androidx.compose.material.icons.rounded.FormatQuote
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.SkipNext
import androidx.compose.material.icons.rounded.SkipPrevious
import androidx.compose.material.icons.rounded.Star
import androidx.compose.material.icons.rounded.StarBorder
import androidx.compose.material.icons.rounded.VolumeDown
import androidx.compose.material.icons.rounded.VolumeUp
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.cadence.player.data.splitArtists
import com.cadence.player.playback.CadencePlayer
import com.cadence.player.playback.durationMs
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun NowPlayingScreen(
    player: CadencePlayer,
    shuffleOn: Boolean,
    appliedGainDb: Float?,
    crossfadeSeconds: Int,
    onArtistClick: ((String) -> Unit)? = null,
    onToggleFavorite: ((Boolean) -> Unit)? = null,
    onEditTags: (() -> Unit)? = null,
    onDeleteTrack: (() -> Unit)? = null,
    onClose: () -> Unit,
) {
    val context = LocalContext.current
    val audioManager = remember { context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager }

    val snap = rememberPlayerSnapshot(player)
    val item = snap.item ?: return
    val art = item.localConfiguration?.uri?.toString()
    val duration = item.durationMs.coerceAtLeast(1L)
    val title = item.mediaMetadata.title?.toString().orEmpty()
    val rawArtist = item.mediaMetadata.artist?.toString().orEmpty()
    val artists = remember(rawArtist) { splitArtists(rawArtist, enabled = true) }

    var showQueue by rememberSaveable { mutableStateOf(false) }
    var isFavorite by remember { mutableStateOf(false) }
    var position by remember { mutableLongStateOf(0L) }
    var dragging by remember { mutableStateOf<Float?>(null) }
    var showMenu by remember { mutableStateOf(false) }

    val maxVol = audioManager?.getStreamMaxVolume(AudioManager.STREAM_MUSIC)?.toFloat() ?: 15f
    var currentVol by remember {
        mutableFloatStateOf(
            audioManager?.getStreamVolume(AudioManager.STREAM_MUSIC)?.toFloat() ?: 0f
        )
    }

    LaunchedEffect(snap.playWhenReady, item) {
        while (true) {
            position = player.currentPosition
            audioManager?.let {
                currentVol = it.getStreamVolume(AudioManager.STREAM_MUSIC).toFloat()
            }
            delay(250)
        }
    }

    val white = Color.White
    val scope = rememberCoroutineScope()
    val dragOffset = remember { Animatable(0f) }
    val dragState = rememberDraggableState { delta ->
        scope.launch { dragOffset.snapTo((dragOffset.value + delta).coerceAtLeast(0f)) }
    }

    Box(
        Modifier
            .fillMaxSize()
            .offset { IntOffset(0, dragOffset.value.roundToInt()) }
            .draggable(
                state = dragState,
                orientation = Orientation.Vertical,
                onDragStopped = { velocity ->
                    if (dragOffset.value > 220f || velocity > 1500f) onClose()
                    else dragOffset.animateTo(0f, spring())
                },
            )
            .background(Color.Black)
    ) {
        val gradient = rememberArtGradient(art)

        // Fullscreen Artwork Background bleeding to top edge
        Box(Modifier.fillMaxSize()) {
            if (!showQueue) {
                Box(
                    Modifier
                        .fillMaxWidth()
                        .fillMaxHeight(0.70f)
                ) {
                    Artwork(
                        art,
                        Modifier.fillMaxSize(),
                        px = 1200,
                        corner = 0.dp
                    )
                }
            }

            // Smooth gradient blending down into dark controls background
            Box(
                Modifier
                    .fillMaxSize()
                    .background(
                        Brush.verticalGradient(
                            colorStops = arrayOf(
                                0.0f to Color.Transparent,
                                0.30f to gradient[0].copy(alpha = 0.3f),
                                0.55f to gradient[1].copy(alpha = 0.90f),
                                0.80f to Color.Black,
                                1.0f to Color.Black
                            )
                        )
                    )
            )
        }

        Column(
            Modifier
                .fillMaxSize()
                .statusBarsPadding()
                .navigationBarsPadding()
                .padding(horizontal = 24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            // Top Drag Handle
            Box(
                Modifier
                    .padding(top = 8.dp, bottom = 12.dp)
                    .size(width = 36.dp, height = 5.dp)
                    .clip(CircleShape)
                    .background(white.copy(alpha = 0.4f))
                    .clickable { onClose() }
            )

            // Animated content transition between Main Now Playing view and Queue view
            AnimatedContent(
                targetState = showQueue,
                transitionSpec = {
                    (slideInVertically(animationSpec = spring(), initialOffsetY = { height -> height }) + fadeIn(animationSpec = spring()))
                        .togetherWith(slideOutVertically(animationSpec = spring(), targetOffsetY = { height -> -height }) + fadeOut(animationSpec = spring()))
                },
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth(),
                label = "NowPlayingQueueTransition"
            ) { inQueue ->
                if (inQueue) {
                    Column(Modifier.fillMaxSize()) {
                        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                            Artwork(art, Modifier.size(56.dp), px = 128, corner = 8.dp)
                            Spacer(Modifier.width(14.dp))
                            Column(Modifier.weight(1f)) {
                                Text(
                                    title,
                                    color = white,
                                    fontSize = 17.sp,
                                    fontWeight = FontWeight.Bold,
                                    maxLines = 1,
                                    modifier = Modifier.basicMarquee()
                                )
                                Text(rawArtist, color = white.copy(alpha = 0.7f), fontSize = 15.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            }
                        }
                        Spacer(Modifier.height(8.dp))
                        QueueList(player, snap, Modifier.weight(1f).fillMaxWidth())
                    }
                } else {
                    Column(
                        Modifier.fillMaxSize(),
                        verticalArrangement = Arrangement.Bottom,
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Spacer(Modifier.weight(1f))
                    }
                }
            }

            // Song Info & Actions Header
            Row(
                Modifier
                    .fillMaxWidth()
                    .padding(bottom = 8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(Modifier.weight(1f)) {
                    Text(
                        title,
                        color = white,
                        fontSize = 22.sp,
                        fontWeight = FontWeight.Bold,
                        maxLines = 1,
                        modifier = Modifier.basicMarquee()
                    )

                    // Clickable Artist Chips
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        artists.forEachIndexed { idx, name ->
                            Text(
                                name,
                                color = white.copy(alpha = 0.75f),
                                fontSize = 17.sp,
                                maxLines = 1,
                                modifier = Modifier.clickable { onArtistClick?.invoke(name) }
                            )
                            if (idx < artists.lastIndex) {
                                Text(",", color = white.copy(alpha = 0.5f), fontSize = 17.sp)
                            }
                        }
                    }
                }

                // Favorite Star Button (Toggles Liked Playlist)
                IconButton(onClick = {
                    isFavorite = !isFavorite
                    onToggleFavorite?.invoke(isFavorite)
                }) {
                    Icon(
                        if (isFavorite) Icons.Rounded.Star else Icons.Rounded.StarBorder,
                        contentDescription = "Favorite",
                        tint = if (isFavorite) Color(0xFFFFD700) else white.copy(alpha = 0.8f),
                        modifier = Modifier.size(28.dp)
                    )
                }

                // Options Overflow Menu
                Box {
                    IconButton(onClick = { showMenu = true }) {
                        Icon(
                            Icons.Rounded.MoreVert,
                            contentDescription = "Options",
                            tint = white.copy(alpha = 0.8f),
                            modifier = Modifier.size(24.dp)
                        )
                    }
                    DropdownMenu(
                        expanded = showMenu,
                        onDismissRequest = { showMenu = false }
                    ) {
                        DropdownMenuItem(
                            text = { Text("Edit tags") },
                            onClick = {
                                showMenu = false
                                onEditTags?.invoke()
                            }
                        )
                        DropdownMenuItem(
                            text = { Text("Delete track", color = MaterialTheme.colorScheme.error) },
                            onClick = {
                                showMenu = false
                                onDeleteTrack?.invoke()
                            }
                        )
                    }
                }
            }

            // Progress Slider
            Slider(
                value = dragging ?: (position.toFloat() / duration).coerceIn(0f, 1f),
                onValueChange = { dragging = it },
                onValueChangeFinished = {
                    dragging?.let { player.seekTo((it * duration).toLong()) }
                    dragging = null
                },
                colors = SliderDefaults.colors(
                    thumbColor = white,
                    activeTrackColor = white,
                    inactiveTrackColor = white.copy(alpha = 0.25f),
                ),
            )
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                val shown = dragging?.let { (it * duration).toLong() } ?: position
                val gainText = appliedGainDb?.let { "ReplayGain %+.1f dB".format(it) } ?: "ReplayGain -- dB"
                Text(formatTime(shown), color = white.copy(alpha = 0.6f), fontSize = 13.sp)
                Text(gainText, color = white.copy(alpha = 0.55f), fontSize = 11.sp, fontWeight = FontWeight.Medium)
                Text("-" + formatTime(duration - shown), color = white.copy(alpha = 0.6f), fontSize = 13.sp)
            }

            // Playback Transport Controls
            Row(
                Modifier
                    .fillMaxWidth()
                    .padding(vertical = 12.dp),
                horizontalArrangement = Arrangement.SpaceEvenly,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                IconButton(onClick = { player.seekToPrevious() }, Modifier.size(64.dp)) {
                    Icon(Icons.Rounded.SkipPrevious, "Previous", tint = white, modifier = Modifier.size(48.dp))
                }
                IconButton(
                    onClick = { if (snap.playWhenReady) player.pause() else player.play() },
                    Modifier.size(72.dp),
                ) {
                    Icon(
                        if (snap.playWhenReady) Icons.Rounded.Pause else Icons.Rounded.PlayArrow,
                        if (snap.playWhenReady) "Pause" else "Play",
                        tint = white, modifier = Modifier.size(64.dp),
                    )
                }
                IconButton(onClick = { player.seekToNext() }, Modifier.size(64.dp)) {
                    Icon(Icons.Rounded.SkipNext, "Next", tint = white, modifier = Modifier.size(48.dp))
                }
            }

            // System Volume Bar
            Surface(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 8.dp)
                    .height(38.dp),
                shape = RoundedCornerShape(20.dp),
                color = white.copy(alpha = 0.15f)
            ) {
                Row(
                    Modifier.padding(horizontal = 14.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(Icons.Rounded.VolumeDown, "Volume Down", tint = white.copy(alpha = 0.7f), modifier = Modifier.size(18.dp))
                    Slider(
                        value = currentVol,
                        onValueChange = { newVal ->
                            currentVol = newVal
                            audioManager?.setStreamVolume(
                                AudioManager.STREAM_MUSIC,
                                newVal.roundToInt(),
                                0
                            )
                        },
                        valueRange = 0f..maxVol,
                        modifier = Modifier
                            .weight(1f)
                            .padding(horizontal = 8.dp),
                        colors = SliderDefaults.colors(
                            thumbColor = Color.Transparent,
                            activeTrackColor = white,
                            inactiveTrackColor = white.copy(alpha = 0.3f)
                        )
                    )
                    Icon(Icons.Rounded.VolumeUp, "Volume Up", tint = white.copy(alpha = 0.7f), modifier = Modifier.size(18.dp))
                }
            }

            // Bottom Tools
            Row(
                Modifier
                    .fillMaxWidth()
                    .padding(vertical = 12.dp),
                horizontalArrangement = Arrangement.SpaceEvenly,
                verticalAlignment = Alignment.CenterVertically
            ) {
                IconButton(onClick = { }) {
                    Icon(Icons.Rounded.FormatQuote, "Lyrics", tint = white.copy(alpha = 0.7f), modifier = Modifier.size(24.dp))
                }
                IconButton(onClick = { }) {
                    Icon(Icons.Rounded.Cast, "Cast", tint = white.copy(alpha = 0.7f), modifier = Modifier.size(24.dp))
                }
                IconButton(onClick = { showQueue = !showQueue }) {
                    Icon(
                        Icons.AutoMirrored.Rounded.QueueMusic,
                        "Queue",
                        tint = if (showQueue) MaterialTheme.colorScheme.primary else white.copy(alpha = 0.7f),
                        modifier = Modifier.size(24.dp)
                    )
                }
            }
        }
    }
}