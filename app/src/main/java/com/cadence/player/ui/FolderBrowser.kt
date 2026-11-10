package com.cadence.player.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.ChevronRight
import androidx.compose.material.icons.rounded.Folder
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.cadence.player.data.Track

private data class FolderEntry(val name: String, val path: String, val count: Int)

private class FolderView(
    val subfolders: List<FolderEntry>,
    /** Songs stored directly in this folder. */
    val direct: List<Track>,
    /** Every song in this folder and all folders below it. */
    val all: List<Track>,
)

/** Folder paths look like "Music/Rock/" (MediaStore RELATIVE_PATH); the root is "". */
private fun folderView(tracks: List<Track>, path: String): FolderView {
    val subs = LinkedHashMap<String, Int>()
    val direct = ArrayList<Track>()
    val all = ArrayList<Track>()
    for (t in tracks) {
        if (!t.folder.startsWith(path)) continue
        all += t
        val rest = t.folder.substring(path.length)
        if (rest.isEmpty()) direct += t else {
            val seg = rest.substringBefore('/')
            subs[seg] = (subs[seg] ?: 0) + 1
        }
    }
    val order = compareBy<Track>({ it.discNumber }, { it.trackNumber }, { it.title.lowercase() })
    return FolderView(
        subfolders = subs.map { (name, n) -> FolderEntry(name, "$path$name/", n) }
            .sortedBy { it.name.lowercase() },
        direct = direct.sortedWith(order),
        all = all.sortedWith(compareBy<Track> { it.folder }.then(order)),
    )
}

private fun parentOf(path: String): String {
    val up = path.trimEnd('/').substringBeforeLast('/', "")
    return if (up.isEmpty()) "" else "$up/"
}

@Composable
fun FolderBrowser(
    tracks: List<Track>,
    playingId: String?,
    path: String,
    onPath: (String) -> Unit,
    actions: TrackActions,
    bottomPadding: Dp,
) {
    val view = remember(tracks, path) { folderView(tracks, path) }
    BackHandler(enabled = path.isNotEmpty()) { onPath(parentOf(path)) }
    SideEffect { actions.selection.pool = view.direct }

    LazyColumn(contentPadding = PaddingValues(bottom = bottomPadding)) {
        item {
            Column(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (path.isNotEmpty()) {
                        IconButton(onClick = { onPath(parentOf(path)) }) {
                            Icon(Icons.AutoMirrored.Rounded.ArrowBack, "Up one folder")
                        }
                    } else {
                        Spacer(Modifier.width(8.dp))
                    }
                    Column(Modifier.weight(1f)) {
                        Text(
                            if (path.isEmpty()) "All folders" else path.trimEnd('/').substringAfterLast('/'),
                            fontSize = 20.sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis,
                        )
                        if (path.isNotEmpty()) {
                            Text(
                                path.trimEnd('/'), fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
                if (view.all.isNotEmpty()) {
                    PlayShuffleButtons(
                        onPlay = { actions.play(view.all, 0, false) },
                        onShuffle = { actions.play(view.all, 0, true) },
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 8.dp),
                    )
                    TextButton(
                        onClick = {
                            val name = if (path.isEmpty()) "All music" else path.trimEnd('/').substringAfterLast('/')
                            actions.saveAsPlaylist(name, view.all)
                        },
                        modifier = Modifier.padding(horizontal = 8.dp),
                    ) { Text("Save ${view.all.size} songs as playlist") }
                }
            }
        }
        items(view.subfolders, key = { it.path }) { f ->
            Row(
                Modifier.fillMaxWidth().clickable { onPath(f.path) }.padding(horizontal = 20.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(Icons.Rounded.Folder, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(32.dp))
                Spacer(Modifier.width(16.dp))
                Column(Modifier.weight(1f)) {
                    Text(f.name, maxLines = 1, overflow = TextOverflow.Ellipsis, fontSize = 16.sp)
                    Text(
                        if (f.count == 1) "1 song" else "${f.count} songs", fontSize = 13.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Icon(Icons.Rounded.ChevronRight, null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        // Tapping a song queues the songs in this folder (not the subfolders), starting from the tapped one.
        itemsIndexed(view.direct, key = { _, t -> t.id }) { i, t ->
            SongRow(t, playingId == t.id.toString(), showArt = true, actions = actions) {
                actions.play(view.direct, i, false)
            }
        }
    }
}
