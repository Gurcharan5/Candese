package com.cadence.player.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.automirrored.rounded.QueueMusic
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.Close
import androidx.compose.foundation.lazy.itemsIndexed as playlistItemsIndexed
import androidx.compose.runtime.saveable.rememberSaveable

import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.cadence.player.data.SortField

@Composable
fun PlaylistsScreen(
    playlists: List<PlaylistView>,
    padding: PaddingValues,
    onOpen: (Long) -> Unit,
    onCreate: () -> Unit,
    onRename: (PlaylistView) -> Unit,
    onDelete: (PlaylistView) -> Unit,
) {
    LazyColumn(
        contentPadding = PaddingValues(
            top = padding.calculateTopPadding(), bottom = padding.calculateBottomPadding() + 16.dp
        )
    ) {
        item {
            Text(
                "Playlists", fontSize = 34.sp, fontWeight = FontWeight.Bold,
                modifier = Modifier.padding(start = 20.dp, top = 16.dp, bottom = 8.dp),
            )
        }
        item {
            TextButton(onClick = onCreate, modifier = Modifier.padding(horizontal = 12.dp)) {
                Icon(Icons.Rounded.Add, null); Spacer(Modifier.width(6.dp)); Text("New playlist")
            }
        }
        if (playlists.isEmpty()) {
            item {
                Text(
                    "No playlists yet. Create one here, from a folder in Library, or by selecting songs.",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp),
                )
            }
        }
        items(playlists, key = { it.id }) { p ->
            var menu by remember { mutableStateOf(false) }
            Row(
                Modifier.fillMaxWidth().clickable { onOpen(p.id) }.padding(start = 20.dp, top = 6.dp, bottom = 6.dp, end = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(
                    Modifier.size(52.dp).clip(RoundedCornerShape(8.dp)).background(MaterialTheme.colorScheme.surfaceVariant),
                    contentAlignment = Alignment.Center,
                ) {
                    val first = p.items.firstOrNull()?.track
                    if (first != null) Artwork(first.uri, Modifier.size(52.dp), px = 128, corner = 8.dp)
                    else Icon(Icons.AutoMirrored.Rounded.QueueMusic, null, tint = MaterialTheme.colorScheme.primary)
                }
                Spacer(Modifier.width(14.dp))
                Column(Modifier.weight(1f)) {
                    Text(p.name, maxLines = 1, overflow = TextOverflow.Ellipsis, fontSize = 16.sp)
                    Text(
                        if (p.items.size == 1) "1 song" else "${p.items.size} songs", fontSize = 13.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Box {
                    IconButton(onClick = { menu = true }) {
                        Icon(Icons.Rounded.MoreVert, "More", tint = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                        DropdownMenuItem(text = { Text("Rename") }, onClick = { menu = false; onRename(p) })
                        DropdownMenuItem(
                            text = { Text("Delete playlist", color = MaterialTheme.colorScheme.error) },
                            onClick = { menu = false; onDelete(p) },
                        )
                    }
                }
            }
        }
    }
}

@Composable
fun PlaylistScreen(
    playlist: PlaylistView,
    playingId: String?,
    padding: PaddingValues,
    actions: TrackActions,
    onBack: () -> Unit,
    onRemoveEntry: (Long) -> Unit,
) {
    val tracks = playlist.tracks
    var sortField by rememberSaveable(playlist.id) { mutableStateOf(SortField.TITLE) }
    var sortDescending by rememberSaveable(playlist.id) { mutableStateOf(false) }
    var query by rememberSaveable(playlist.id) { mutableStateOf("") }
    val filteredItems = remember(playlist.items, query, sortField, sortDescending) {
        val q = query.trim()
        val filtered = if (q.isBlank()) playlist.items else playlist.items.filter {
            it.track.title.contains(q, true) || it.track.artist.contains(q, true) || it.track.album.contains(q, true)
        }
        val order = sortTracks(filtered.map { it.track }, sortField, sortDescending)
            .mapIndexed { index, track -> track.id to index }.toMap()
        filtered.sortedBy { order[it.track.id] ?: Int.MAX_VALUE }
    }
    val visibleTracks = filteredItems.map { it.track }
    SideEffect { actions.selection.pool = visibleTracks }
    LazyColumn(
        contentPadding = PaddingValues(
            top = padding.calculateTopPadding(), bottom = padding.calculateBottomPadding() + 16.dp
        )
    ) {
        item {
            Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp)) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, "Back") }
                }
                Text(playlist.name, fontSize = 28.sp, fontWeight = FontWeight.Bold, maxLines = 2, overflow = TextOverflow.Ellipsis)
                Text(
                    if (tracks.size == 1) "1 song" else "${tracks.size} songs", fontSize = 14.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(12.dp))
                if (tracks.isNotEmpty()) {
                    PlayShuffleButtons(
                        onPlay = { actions.play(tracks, 0, false) },
                        onShuffle = { actions.play(tracks, 0, true) },
                    )
                } else {
                    Text(
                        "This playlist is empty. Use the menu on any song to add it here.",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                OutlinedTextField(
                    value = query,
                    onValueChange = { query = it },
                    placeholder = { Text("Search this playlist") },
                    leadingIcon = { Icon(Icons.Rounded.Search, null) },
                    trailingIcon = {
                        if (query.isNotEmpty()) IconButton(onClick = { query = "" }) {
                            Icon(Icons.Rounded.Close, "Clear search")
                        }
                    },
                    singleLine = true,
                    shape = RoundedCornerShape(16.dp),
                    modifier = Modifier.fillMaxWidth(),
                )
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("${filteredItems.size} of ${tracks.size} songs", fontSize = 13.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.weight(1f))
                    SortControl(
                        field = sortField, descending = sortDescending,
                        onChange = { field, descending -> sortField = field; sortDescending = descending },
                    )
                }
                Spacer(Modifier.height(8.dp))
            }
        }
        playlistItemsIndexed(filteredItems, key = { _, entry -> entry.entryId }) { i, item ->
            SongRow(
                item.track, playingId == item.track.id.toString(), showArt = true, actions = actions,
                onRemoveFromPlaylist = { onRemoveEntry(item.entryId) },
            ) { actions.play(visibleTracks, i, false) }
        }
    }
}
