package com.cadence.player.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import com.cadence.player.playback.durationMs

/** The playing track followed by everything still to come. Tap a row to jump to it. */
@Composable
fun QueueList(player: Player, snap: PlayerSnapshot, modifier: Modifier = Modifier) {
    val current = snap.index
    val upNext = (snap.queueSize - current - 1).coerceAtLeast(0)

    LazyColumn(modifier) {
        item { QueueHeader("Now playing") }
        if (current in 0 until snap.queueSize) {
            item(key = "now") { QueueRow(player.getMediaItemAt(current), active = true) { } }
        }
        item { QueueHeader(if (upNext == 0) "Up next" else "Up next · $upNext") }
        if (upNext == 0) {
            item {
                Text(
                    "Nothing queued after this song. Use the menu on any song to add it here.",
                    color = Color.White.copy(alpha = 0.6f), fontSize = 14.sp,
                    modifier = Modifier.padding(vertical = 8.dp),
                )
            }
        }
        items(count = upNext, key = { "q${snap.version}-$it" }) { k ->
            val i = current + 1 + k
            QueueRow(
                player.getMediaItemAt(i), active = false,
                onRemove = { player.removeMediaItem(i) },
            ) {
                player.seekTo(i, 0L)
                player.play()
            }
        }
    }
}

@Composable
private fun QueueHeader(text: String) {
    Text(
        text, color = Color.White, fontSize = 17.sp, fontWeight = FontWeight.Bold,
        modifier = Modifier.padding(top = 12.dp, bottom = 6.dp),
    )
}

@Composable
private fun QueueRow(item: MediaItem, active: Boolean, onRemove: (() -> Unit)? = null, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onClick).padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Artwork(item.localConfiguration?.uri?.toString(), Modifier.size(44.dp), px = 128, corner = 6.dp)
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(
                item.mediaMetadata.title?.toString().orEmpty(),
                color = if (active) MaterialTheme.colorScheme.primary else Color.White,
                maxLines = 1, overflow = TextOverflow.Ellipsis, fontSize = 15.sp,
            )
            Text(
                item.mediaMetadata.artist?.toString().orEmpty(),
                color = Color.White.copy(alpha = 0.6f), maxLines = 1, overflow = TextOverflow.Ellipsis, fontSize = 13.sp,
            )
        }
        val d = item.durationMs
        if (d > 0) Text(formatTime(d), color = Color.White.copy(alpha = 0.5f), fontSize = 12.sp)
        if (onRemove != null) {
            IconButton(onClick = onRemove, Modifier.size(36.dp)) {
                Icon(Icons.Rounded.Close, "Remove from queue", tint = Color.White.copy(alpha = 0.6f), modifier = Modifier.size(18.dp))
            }
        }
    }
}
