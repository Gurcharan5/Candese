package com.cadence.player.ui

import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import android.util.LruCache
import android.util.Size
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.MusicNote
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.media3.common.MediaItem
import androidx.palette.graphics.Palette
import androidx.media3.common.Player
import androidx.media3.common.Timeline
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.concurrent.ConcurrentHashMap

object ArtworkCache {
    private val cache = object : LruCache<String, Bitmap>(48 * 1024 * 1024) {
        override fun sizeOf(key: String, value: Bitmap) = value.byteCount
    }
    private val missing = ConcurrentHashMap.newKeySet<String>()

    suspend fun load(context: Context, uri: Uri, px: Int): Bitmap? {
        val key = "$uri@$px"
        cache.get(key)?.let { return it }
        if (key in missing) return null
        return withContext(Dispatchers.IO) {
            try {
                context.contentResolver.loadThumbnail(uri, Size(px, px), null).also { cache.put(key, it) }
            } catch (e: Exception) {
                missing.add(key)
                null
            }
        }
    }
}

/** Album art pulled from the audio file's own content URI (embedded or folder art). */
@Composable
fun Artwork(
    trackUri: String?,
    modifier: Modifier = Modifier,
    px: Int = 256,
    corner: Dp = 8.dp,
) {
    val ctx = LocalContext.current
    val bitmap by produceState<Bitmap?>(null, trackUri, px) {
        value = trackUri?.let { ArtworkCache.load(ctx, Uri.parse(it), px) }
    }
    Box(
        modifier
            .clip(RoundedCornerShape(corner))
            .background(MaterialTheme.colorScheme.surfaceVariant),
        contentAlignment = Alignment.Center,
    ) {
        val b = bitmap
        if (b != null) {
            val image = remember(b) { b.asImageBitmap() }
            Image(image, null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
        } else {
            Icon(
                Icons.Rounded.MusicNote, null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)
            )
        }
    }
}

data class PlayerSnapshot(
    val item: MediaItem?,
    val playWhenReady: Boolean,
    val state: Int,
    val index: Int,
    val queueSize: Int,
    /** Bumped whenever the queue contents or order change. */
    val version: Int,
)

@Composable
fun rememberPlayerSnapshot(player: Player): PlayerSnapshot {
    var snap by remember(player) {
        mutableStateOf(
            PlayerSnapshot(
                player.currentMediaItem, player.playWhenReady, player.playbackState,
                player.currentMediaItemIndex, player.mediaItemCount, 0
            )
        )
    }
    DisposableEffect(player) {
        var version = 0
        val listener = object : Player.Listener {
            override fun onTimelineChanged(timeline: Timeline, reason: Int) {
                version++
            }

            override fun onEvents(p: Player, events: Player.Events) {
                snap = PlayerSnapshot(
                    p.currentMediaItem, p.playWhenReady, p.playbackState,
                    p.currentMediaItemIndex, p.mediaItemCount, version
                )
            }
        }
        player.addListener(listener)
        onDispose { player.removeListener(listener) }
    }
    return snap
}

fun formatTime(ms: Long): String {
    val s = (ms / 1000).coerceAtLeast(0)
    return "%d:%02d".format(s / 60, s % 60)
}

fun formatDuration(ms: Long): String {
    val minutes = ms / 60_000
    val hours = minutes / 60
    return when {
        ms <= 0 -> "0m"
        minutes < 1 -> "<1m"
        hours < 1 -> "${minutes}m"
        hours < 100 -> "${hours}h ${minutes % 60}m"
        else -> "${hours / 24}d ${hours % 24}h"
    }
}

private val DefaultGradient = listOf(Color(0xFF3A3A3C), Color(0xFF1C1C1E), Color.Black)

private fun paletteGradient(bitmap: Bitmap): List<Color> {
    val p = Palette.from(bitmap).maximumColorCount(16).generate()
    val primary = Color(p.vibrantSwatch?.rgb ?: p.dominantSwatch?.rgb ?: p.mutedSwatch?.rgb ?: return DefaultGradient)
    val secondary = Color(p.darkVibrantSwatch?.rgb ?: p.darkMutedSwatch?.rgb ?: p.dominantSwatch?.rgb ?: primary.toArgb())
    // Darkened so white text stays readable on any artwork, even very bright ones.
    val top = lerp(primary, Color.Black, if (primary.luminance() > 0.35f) 0.55f else 0.25f)
    val middle = lerp(secondary, Color.Black, 0.5f)
    val bottom = lerp(secondary, Color.Black, 0.82f)
    return listOf(top, middle, bottom)
}

/** Three gradient stops (top, middle, bottom) taken from the artwork's palette, cross-fading between tracks. */
@Composable
fun rememberArtGradient(trackUri: String?): List<Color> {
    val ctx = LocalContext.current
    val target by produceState(DefaultGradient, trackUri) {
        val bitmap = trackUri?.let { ArtworkCache.load(ctx, Uri.parse(it), 128) }
        value = if (bitmap == null) DefaultGradient else withContext(Dispatchers.Default) { paletteGradient(bitmap) }
    }
    val c0 by animateColorAsState(target[0], tween(700), label = "g0")
    val c1 by animateColorAsState(target[1], tween(700), label = "g1")
    val c2 by animateColorAsState(target[2], tween(700), label = "g2")
    return listOf(c0, c1, c2)
}
