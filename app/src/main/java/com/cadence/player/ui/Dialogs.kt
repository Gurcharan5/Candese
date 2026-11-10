package com.cadence.player.ui

import android.net.Uri
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.QueueMusic
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.cadence.player.data.EditableTags
import com.cadence.player.data.TagEditor
import com.cadence.player.data.Track

@Composable
fun TextPromptDialog(
    title: String,
    initial: String,
    confirmLabel: String,
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit,
) {
    var text by remember { mutableStateOf(initial) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = { OutlinedTextField(text, { text = it }, singleLine = true, modifier = Modifier.fillMaxWidth()) },
        confirmButton = { TextButton(onClick = { onConfirm(text.trim()) }, enabled = text.isNotBlank()) { Text(confirmLabel) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

@Composable
fun ConfirmDialog(
    title: String,
    message: String,
    confirmLabel: String,
    onDismiss: () -> Unit,
    onConfirm: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = { Text(message) },
        confirmButton = {
            TextButton(onClick = onConfirm) { Text(confirmLabel, color = MaterialTheme.colorScheme.error) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

@Composable
fun AddToPlaylistDialog(
    count: Int,
    playlists: List<PlaylistView>,
    onDismiss: () -> Unit,
    onPick: (PlaylistView) -> Unit,
    onCreate: (String) -> Unit,
) {
    var creating by remember { mutableStateOf(false) }
    if (creating) {
        TextPromptDialog("New playlist", "", "Create", onDismiss = { creating = false }, onConfirm = onCreate)
        return
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (count == 1) "Add song to…" else "Add $count songs to…") },
        text = {
            LazyColumn(Modifier.heightIn(max = 320.dp)) {
                item {
                    Row(
                        Modifier.fillMaxWidth().clickable { creating = true }.padding(vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(Icons.Rounded.Add, null, tint = MaterialTheme.colorScheme.primary)
                        Spacer(Modifier.width(12.dp))
                        Text("New playlist…", color = MaterialTheme.colorScheme.primary)
                    }
                }
                items(playlists, key = { it.id }) { p ->
                    Row(
                        Modifier.fillMaxWidth().clickable { onPick(p) }.padding(vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(Icons.AutoMirrored.Rounded.QueueMusic, null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
                        Spacer(Modifier.width(12.dp))
                        Column(Modifier.weight(1f)) {
                            Text(p.name, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text(
                                if (p.items.size == 1) "1 song" else "${p.items.size} songs",
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                style = MaterialTheme.typography.bodySmall,
                            )
                        }
                    }
                }
            }
        },
        confirmButton = {},
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

private sealed interface TagLoad {
    data object Loading : TagLoad
    data object Failed : TagLoad
    class Ok(val tags: EditableTags) : TagLoad
}

@Composable
fun EditTagsDialog(track: Track, onDismiss: () -> Unit, onSave: (EditableTags) -> Unit) {
    val context = LocalContext.current
    val load by produceState<TagLoad>(TagLoad.Loading, track.id) {
        value = runCatching { TagLoad.Ok(TagEditor.read(context, Uri.parse(track.uri))) }
            .getOrElse { TagLoad.Failed }
    }
    var draft by remember { mutableStateOf<EditableTags?>(null) }
    LaunchedEffect(load) { (load as? TagLoad.Ok)?.let { draft = it.tags } }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Edit tags") },
        text = {
            val d = draft
            when {
                load is TagLoad.Failed -> Text("This file's tags couldn't be read, so it can't be edited here.")
                d == null -> CircularProgressIndicator(Modifier.size(32.dp))
                else -> Column(Modifier.verticalScroll(rememberScrollState())) {
                    TagField("Title", d.title) { draft = d.copy(title = it) }
                    TagField("Artist", d.artist) { draft = d.copy(artist = it) }
                    TagField("Album", d.album) { draft = d.copy(album = it) }
                    TagField("Album artist", d.albumArtist) { draft = d.copy(albumArtist = it) }
                    TagField("Genre", d.genre) { draft = d.copy(genre = it) }
                    TagField("Year", d.year) { draft = d.copy(year = it) }
                    TagField("Track number", d.track) { draft = d.copy(track = it) }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { draft?.let(onSave) }, enabled = draft != null) { Text("Save") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

@Composable
private fun TagField(label: String, value: String, onChange: (String) -> Unit) {
    OutlinedTextField(
        value = value, onValueChange = onChange, label = { Text(label) }, singleLine = true,
        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
    )
}
