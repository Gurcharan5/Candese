package com.cadence.player.ui

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
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
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.RadioButtonUnchecked
import androidx.compose.material.icons.rounded.Shuffle
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.cadence.player.data.Track
import com.cadence.player.data.splitArtists
import androidx.compose.material.icons.automirrored.rounded.Sort
import androidx.compose.material.icons.rounded.ArrowDownward
import androidx.compose.material.icons.rounded.ArrowUpward
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.TextButton
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.foundation.lazy.items as lazyItems
import com.cadence.player.data.Settings
import com.cadence.player.data.SortField

/** Everything a song list needs: playback, queue, playlists, tag editing, deleting and multi-select. */
class TrackActions(
    val play: (tracks: List<Track>, startIndex: Int, shuffle: Boolean) -> Unit,
    val playNext: (List<Track>) -> Unit,
    val addToQueue: (List<Track>) -> Unit,
    val addToPlaylist: (List<Track>) -> Unit,
    val saveAsPlaylist: (suggestedName: String, tracks: List<Track>) -> Unit,
    val editTags: (Track) -> Unit,
    val delete: (List<Track>) -> Unit,
    val selection: Selection,
)

@Composable
fun LibraryScreen(
    albums: List<Album>,
    tracks: List<Track>,
    playingId: String?,
    padding: PaddingValues,
    actions: TrackActions,
    settings: Settings,
    ui: LibraryUiState,
    onAlbum: (Long) -> Unit,
    onArtist: (String) -> Unit,
) {
    val options = listOf("Albums", "Songs", "Artists", "Folders")
    val sortField by settings.songSort.collectAsStateWithLifecycle()
    val sortDescending by settings.songSortDescending.collectAsStateWithLifecycle()
    val sorted = remember(tracks, sortField, sortDescending) { sortTracks(tracks, sortField, sortDescending) }
    val query = ui.query.trim()
    val bottom = padding.calculateBottomPadding() + 16.dp

    Column(Modifier.fillMaxSize().padding(top = padding.calculateTopPadding())) {
        Text(
            "Library",
            fontSize = 34.sp,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.padding(start = 20.dp, top = 16.dp, bottom = 8.dp),
        )
        OutlinedTextField(
            value = ui.query,
            onValueChange = { ui.query = it },
            placeholder = { Text("Search songs, artists, albums") },
            leadingIcon = { Icon(Icons.Rounded.Search, null) },
            trailingIcon = {
                if (ui.query.isNotEmpty()) {
                    IconButton(onClick = { ui.query = "" }) { Icon(Icons.Rounded.Close, "Clear search") }
                }
            },
            singleLine = true,
            shape = RoundedCornerShape(12.dp),
            modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp),
        )

        if (query.isNotEmpty()) {
            val q = query.lowercase()
            val songResults = remember(sorted, q) {
                sorted.filter { it.title.lowercase().contains(q) || it.artist.lowercase().contains(q) || it.album.lowercase().contains(q) }
            }
            val albumResults = remember(albums, q) {
                albums.filter { it.title.lowercase().contains(q) || it.artist.lowercase().contains(q) }
            }
            SideEffect { actions.selection.pool = songResults }
            LazyColumn(contentPadding = PaddingValues(top = 8.dp, bottom = bottom)) {
                if (albumResults.isNotEmpty()) {
                    item { ResultsHeader("Albums") }
                    lazyItems(albumResults, key = { "a${it.id}" }) { album ->
                        Row(
                            Modifier.fillMaxWidth().clickable { onAlbum(album.id) }.padding(horizontal = 20.dp, vertical = 6.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Artwork(album.tracks.first().uri, Modifier.size(48.dp), px = 128, corner = 6.dp)
                            Spacer(Modifier.width(14.dp))
                            Column(Modifier.weight(1f)) {
                                Text(album.title, maxLines = 1, overflow = TextOverflow.Ellipsis, fontSize = 16.sp)
                                Text(
                                    album.artist, maxLines = 1, overflow = TextOverflow.Ellipsis, fontSize = 13.sp,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                    }
                }
                if (songResults.isNotEmpty()) {
                    item { ResultsHeader("Songs") }
                    itemsIndexed(songResults, key = { _, t -> "s${t.id}" }) { i, t ->
                        SongRow(t, playingId == t.id.toString(), showArt = true, actions = actions) {
                            actions.play(songResults, i, false)
                        }
                    }
                }
                if (albumResults.isEmpty() && songResults.isEmpty()) {
                    item {
                        Text(
                            "No results for \"$query\"",
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(horizontal = 20.dp, vertical = 16.dp),
                        )
                    }
                }
            }
            return@Column
        }

        SingleChoiceSegmentedButtonRow(Modifier.padding(horizontal = 20.dp, vertical = 8.dp).fillMaxWidth()) {
            options.forEachIndexed { i, label ->
                SegmentedButton(
                    selected = ui.section == i,
                    onClick = { ui.section = i },
                    shape = SegmentedButtonDefaults.itemShape(i, options.size),
                ) { Text(label) }
            }
        }
        when (ui.section) {
            0 -> LazyVerticalGrid(
                columns = GridCells.Fixed(2),
                contentPadding = PaddingValues(start = 20.dp, end = 20.dp, top = 8.dp, bottom = bottom),
                horizontalArrangement = Arrangement.spacedBy(16.dp),
                verticalArrangement = Arrangement.spacedBy(20.dp),
            ) {
                items(albums, key = { it.id }) { album ->
                    Column(Modifier.clickable { onAlbum(album.id) }) {
                        Artwork(
                            album.tracks.first().uri,
                            Modifier.fillMaxWidth().aspectRatio(1f),
                            px = 400, corner = 10.dp,
                        )
                        Spacer(Modifier.height(8.dp))
                        Text(album.title, maxLines = 1, overflow = TextOverflow.Ellipsis, fontSize = 14.sp, fontWeight = FontWeight.Medium)
                        Text(
                            album.artist, maxLines = 1, overflow = TextOverflow.Ellipsis, fontSize = 13.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
            1 -> {
                SideEffect { actions.selection.pool = sorted }
                LazyColumn(contentPadding = PaddingValues(bottom = bottom)) {
                    item {
                        Column {
                            PlayShuffleButtons(
                                onPlay = { actions.play(sorted, 0, false) },
                                onShuffle = { actions.play(sorted, 0, true) },
                                modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp),
                            )
                            SortControl(
                                field = sortField, descending = sortDescending,
                                onChange = { f, d -> settings.setSongSort(f, d) },
                                modifier = Modifier.padding(horizontal = 12.dp),
                            )
                        }
                    }
                    itemsIndexed(sorted, key = { _, t -> t.id }) { i, t ->
                        SongRow(t, playingId == t.id.toString(), showArt = true, actions = actions) {
                            actions.play(sorted, i, false)
                        }
                    }
                }
            }
            2 -> ArtistListScreen(
                tracks = tracks,
                onArtist = onArtist,
                bottomPadding = bottom,
            )
            else -> FolderBrowser(
                tracks = tracks, playingId = playingId, path = ui.folderPath, onPath = { ui.folderPath = it },
                actions = actions, bottomPadding = bottom,
            )
        }
    }
}

@Composable
private fun ResultsHeader(text: String) {
    Text(
        text, fontSize = 20.sp, fontWeight = FontWeight.Bold,
        modifier = Modifier.padding(start = 20.dp, top = 12.dp, bottom = 4.dp),
    )
}

fun sortTracks(list: List<Track>, field: SortField, descending: Boolean): List<Track> {
    val comparator: Comparator<Track> = when (field) {
        SortField.TITLE -> compareBy<Track> { it.title.lowercase() }
        SortField.ARTIST -> compareBy<Track> { it.artist.lowercase() }
            .thenBy { it.album.lowercase() }.thenBy { it.discNumber }.thenBy { it.trackNumber }
        SortField.ALBUM -> compareBy<Track> { it.album.lowercase() }
            .thenBy { it.discNumber }.thenBy { it.trackNumber }
        SortField.DATE_MODIFIED -> compareBy<Track> { it.dateModified }
        SortField.DURATION -> compareBy<Track> { it.durationMs }
        SortField.YEAR -> compareBy<Track> { it.year }
    }
    return list.sortedWith(if (descending) comparator.reversed() else comparator)
}

@Composable
fun SortControl(
    field: SortField,
    descending: Boolean,
    onChange: (SortField, Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    var open by remember { mutableStateOf(false) }
    Box(modifier) {
        TextButton(onClick = { open = true }) {
            Icon(Icons.AutoMirrored.Rounded.Sort, null, Modifier.size(18.dp))
            Spacer(Modifier.width(6.dp))
            Text(field.label)
            Spacer(Modifier.width(4.dp))
            Icon(
                if (descending) Icons.Rounded.ArrowDownward else Icons.Rounded.ArrowUpward,
                if (descending) "Descending" else "Ascending",
                Modifier.size(16.dp),
            )
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            SortField.entries.forEach { f ->
                DropdownMenuItem(
                    text = {
                        Text(
                            f.label,
                            color = if (f == field) MaterialTheme.colorScheme.primary else Color.Unspecified,
                            fontWeight = if (f == field) FontWeight.SemiBold else FontWeight.Normal,
                        )
                    },
                    // Newest first is the natural default for date modified; everything else starts ascending.
                    onClick = { open = false; onChange(f, if (f == field) descending else f == SortField.DATE_MODIFIED) },
                    trailingIcon = { if (f == field) Icon(Icons.Rounded.Check, null, tint = MaterialTheme.colorScheme.primary) },
                )
            }
            HorizontalDivider()
            DropdownMenuItem(
                text = { Text(if (descending) "Ascending order" else "Descending order") },
                onClick = { open = false; onChange(field, !descending) },
            )
        }
    }
}

@Composable
fun PlayShuffleButtons(onPlay: () -> Unit, onShuffle: () -> Unit, modifier: Modifier = Modifier) {
    val shape = RoundedCornerShape(10.dp)
    val colors = ButtonDefaults.buttonColors(
        containerColor = MaterialTheme.colorScheme.surfaceVariant,
        contentColor = MaterialTheme.colorScheme.primary,
    )
    Row(modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Button(onClick = onPlay, Modifier.weight(1f), shape = shape, colors = colors) {
            Icon(Icons.Rounded.PlayArrow, null); Spacer(Modifier.width(6.dp)); Text("Play")
        }
        Button(onClick = onShuffle, Modifier.weight(1f), shape = shape, colors = colors) {
            Icon(Icons.Rounded.Shuffle, null); Spacer(Modifier.width(6.dp)); Text("Shuffle")
        }
    }
}

/**
 * One song. Tap plays, long-press (or the menu's "Select") starts multi-select, after which taps toggle rows.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun SongRow(
    track: Track,
    playing: Boolean,
    showArt: Boolean,
    actions: TrackActions,
    number: Int? = null,
    onRemoveFromPlaylist: (() -> Unit)? = null,
    onClick: () -> Unit,
) {
    val selection = actions.selection
    val selected = track.id in selection.ids
    val accent = MaterialTheme.colorScheme.primary
    val muted = MaterialTheme.colorScheme.onSurfaceVariant

    Row(
        Modifier.fillMaxWidth()
            .background(if (selected) accent.copy(alpha = 0.14f) else Color.Transparent)
            .combinedClickable(
                onClick = { if (selection.active) selection.toggle(track.id) else onClick() },
                onLongClick = { selection.toggle(track.id) },
            )
            .padding(start = 20.dp, top = 4.dp, bottom = 4.dp, end = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (selection.active) {
            Icon(
                if (selected) Icons.Rounded.CheckCircle else Icons.Rounded.RadioButtonUnchecked, null,
                tint = if (selected) accent else muted, modifier = Modifier.size(24.dp),
            )
            Spacer(Modifier.width(12.dp))
        }
        if (showArt) {
            Artwork(track.uri, Modifier.size(48.dp), px = 128, corner = 6.dp)
            Spacer(Modifier.width(14.dp))
        } else if (number != null) {
            Text(number.toString(), Modifier.width(28.dp), fontSize = 15.sp, color = if (playing) accent else muted)
        }
        Column(Modifier.weight(1f)) {
            Text(
                track.title, maxLines = 1, overflow = TextOverflow.Ellipsis, fontSize = 16.sp,
                color = if (playing) accent else MaterialTheme.colorScheme.onSurface
            )
            if (showArt) {
                Text(track.artist, maxLines = 1, overflow = TextOverflow.Ellipsis, fontSize = 13.sp, color = muted)
            }
        }
        Text(formatTime(track.durationMs), fontSize = 13.sp, color = muted)
        if (selection.active) Spacer(Modifier.width(16.dp)) else TrackMenu(track, actions, onRemoveFromPlaylist)
    }
}

@Composable
private fun TrackMenu(track: Track, actions: TrackActions, onRemoveFromPlaylist: (() -> Unit)?) {
    var open by remember { mutableStateOf(false) }
    Box {
        IconButton(onClick = { open = true }) {
            Icon(Icons.Rounded.MoreVert, "More", tint = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            DropdownMenuItem(text = { Text("Play next") }, onClick = { open = false; actions.playNext(listOf(track)) })
            DropdownMenuItem(text = { Text("Add to queue") }, onClick = { open = false; actions.addToQueue(listOf(track)) })
            DropdownMenuItem(text = { Text("Add to playlist") }, onClick = { open = false; actions.addToPlaylist(listOf(track)) })
            DropdownMenuItem(text = { Text("Edit tags") }, onClick = { open = false; actions.editTags(track) })
            DropdownMenuItem(text = { Text("Select") }, onClick = { open = false; actions.selection.toggle(track.id) })
            if (onRemoveFromPlaylist != null) {
                DropdownMenuItem(text = { Text("Remove from playlist") }, onClick = { open = false; onRemoveFromPlaylist() })
            }
            DropdownMenuItem(
                text = { Text("Delete from device", color = MaterialTheme.colorScheme.error) },
                onClick = { open = false; actions.delete(listOf(track)) },
            )
        }
    }
}

@Composable
fun AlbumScreen(
    album: Album,
    playingId: String?,
    padding: PaddingValues,
    actions: TrackActions,
    onBack: () -> Unit,
) {
    SideEffect { actions.selection.pool = album.tracks }
    LazyColumn(
        contentPadding = PaddingValues(
            top = padding.calculateTopPadding(), bottom = padding.calculateBottomPadding() + 16.dp
        )
    ) {
        item {
            Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                Row(Modifier.fillMaxWidth()) {
                    IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, "Back") }
                }
                Artwork(album.tracks.first().uri, Modifier.fillMaxWidth(0.7f).aspectRatio(1f), px = 600, corner = 12.dp)
                Spacer(Modifier.height(16.dp))
                Text(album.title, fontSize = 22.sp, fontWeight = FontWeight.Bold, maxLines = 2, overflow = TextOverflow.Ellipsis)
                Text(album.artist, fontSize = 18.sp, color = MaterialTheme.colorScheme.primary)
                val year = album.tracks.firstOrNull { it.year > 0 }?.year
                if (year != null) {
                    Text(year.toString(), fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Spacer(Modifier.height(16.dp))
                PlayShuffleButtons(
                    onPlay = { actions.play(album.tracks, 0, false) },
                    onShuffle = { actions.play(album.tracks, 0, true) },
                )
                Spacer(Modifier.height(12.dp))
            }
        }
        itemsIndexed(album.tracks, key = { _, t -> t.id }) { i, t ->
            SongRow(t, playingId == t.id.toString(), showArt = false, actions = actions, number = i + 1) {
                actions.play(album.tracks, i, false)
            }
        }
    }
}


/** Artists are derived from the comma-separated artist metadata on each track. */
fun tracksForArtist(tracks: List<Track>, artist: String): List<Track> =
    tracks.filter { track -> splitArtists(track.artist, enabled = true).any { it.equals(artist, ignoreCase = true) } }
        .sortedWith(compareBy<Track>({ it.album.lowercase() }, { it.discNumber }, { it.trackNumber }, { it.title.lowercase() }))

@Composable
fun ArtistListScreen(
    tracks: List<Track>,
    onArtist: (String) -> Unit,
    bottomPadding: androidx.compose.ui.unit.Dp,
) {
    val artists = remember(tracks) {
        tracks.flatMap { splitArtists(it.artist, enabled = true) }
            .distinctBy { it.lowercase() }
            .sortedBy { it.lowercase() }
    }
    LazyColumn(contentPadding = PaddingValues(bottom = bottomPadding)) {
        lazyItems(artists, key = { it.lowercase() }) { artist ->
            val count = remember(tracks, artist) { tracksForArtist(tracks, artist).size }
            Row(
                Modifier.fillMaxWidth().clickable { onArtist(artist) }
                    .padding(horizontal = 20.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(
                    Modifier.size(48.dp).clip(RoundedCornerShape(16.dp))
                        .background(MaterialTheme.colorScheme.secondaryContainer),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(artist.take(1).uppercase(), color = MaterialTheme.colorScheme.onSecondaryContainer,
                        fontSize = 20.sp, fontWeight = FontWeight.Bold)
                }
                Spacer(Modifier.width(14.dp))
                Column(Modifier.weight(1f)) {
                    Text(artist, fontSize = 16.sp, fontWeight = FontWeight.Medium)
                    Text("$count ${if (count == 1) "song" else "songs"}",
                        fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
    }
}

@Composable
fun ArtistScreen(
    artist: String,
    tracks: List<Track>,
    playingId: String?,
    padding: PaddingValues,
    actions: TrackActions,
    onBack: () -> Unit,
) {
    val artistTracks = remember(tracks, artist) { tracksForArtist(tracks, artist) }
    SideEffect { actions.selection.pool = artistTracks }
    LazyColumn(contentPadding = PaddingValues(
        top = padding.calculateTopPadding(), bottom = padding.calculateBottomPadding() + 16.dp
    )) {
        item {
            Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp)) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, "Back") }
                }
                Text(artist, fontSize = 30.sp, fontWeight = FontWeight.Bold,
                    maxLines = 2, overflow = TextOverflow.Ellipsis)
                Text("${artistTracks.size} ${if (artistTracks.size == 1) "song" else "songs"}",
                    fontSize = 14.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.height(12.dp))
                PlayShuffleButtons(
                    onPlay = { actions.play(artistTracks, 0, false) },
                    onShuffle = { actions.play(artistTracks, 0, true) },
                )
                Spacer(Modifier.height(8.dp))
            }
        }
        itemsIndexed(artistTracks, key = { _, t -> t.id }) { i, track ->
            SongRow(track, playingId == track.id.toString(), showArt = true, actions = actions) {
                actions.play(artistTracks, i, false)
            }
        }
    }
}
