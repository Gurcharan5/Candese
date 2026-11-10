package com.cadence.player.ui

import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.PlaylistAdd
import androidx.compose.material.icons.automirrored.rounded.QueueMusic
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.SelectAll
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.cadence.player.data.Track

/** Multi-select state shared by every song list. Selecting the first row (long press or menu) starts selection mode. */
class Selection {
    var ids by mutableStateOf<Set<Long>>(emptySet())
        private set

    /** The songs on screen, so "select all" knows what "all" is. Set by whichever list is visible. */
    var pool: List<Track> = emptyList()

    val active: Boolean get() = ids.isNotEmpty()

    fun toggle(id: Long) {
        ids = if (id in ids) ids - id else ids + id
    }

    fun selectAll() {
        ids = pool.mapTo(LinkedHashSet()) { it.id }
    }

    fun clear() {
        ids = emptySet()
    }
}

@Composable
fun SelectionBar(
    count: Int,
    onClose: () -> Unit,
    onSelectAll: () -> Unit,
    onAddToPlaylist: () -> Unit,
    onAddToQueue: () -> Unit,
    onDelete: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(modifier.fillMaxWidth(), color = MaterialTheme.colorScheme.surfaceVariant, tonalElevation = 4.dp) {
        Row(
            Modifier.statusBarsPadding().padding(horizontal = 4.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = onClose) { Icon(Icons.Rounded.Close, "Cancel selection") }
            Text("$count selected", Modifier.weight(1f), fontWeight = FontWeight.SemiBold)
            IconButton(onClick = onSelectAll) { Icon(Icons.Rounded.SelectAll, "Select all") }
            IconButton(onClick = onAddToPlaylist) { Icon(Icons.AutoMirrored.Rounded.PlaylistAdd, "Add to playlist") }
            IconButton(onClick = onAddToQueue) { Icon(Icons.AutoMirrored.Rounded.QueueMusic, "Add to queue") }
            IconButton(onClick = onDelete) { Icon(Icons.Rounded.Delete, "Delete", tint = MaterialTheme.colorScheme.error) }
        }
    }
}
