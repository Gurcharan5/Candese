package com.cadence.player

import android.Manifest
import android.app.RecoverableSecurityException
import android.content.IntentSender
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.MediaStore
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.QueueMusic
import androidx.compose.material.icons.rounded.Home
import androidx.compose.material.icons.rounded.LibraryMusic
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material.icons.rounded.SkipNext
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.lifecycleScope
import androidx.media3.common.Player
import com.cadence.player.data.EditableTags
import com.cadence.player.data.Settings
import com.cadence.player.data.TagEditor
import com.cadence.player.data.Track
import com.cadence.player.playback.CadencePlayer
import com.cadence.player.ui.AddToPlaylistDialog
import com.cadence.player.ui.AlbumScreen
import com.cadence.player.ui.ArtistScreen
import com.cadence.player.ui.Artwork
import com.cadence.player.ui.CadenceTheme
import com.cadence.player.ui.ConfirmDialog
import com.cadence.player.ui.EditTagsDialog
import com.cadence.player.ui.HomeScreen
import com.cadence.player.ui.LibraryScreen
import com.cadence.player.ui.LibraryViewModel
import com.cadence.player.ui.NowPlayingScreen
import com.cadence.player.ui.PlayerSnapshot
import com.cadence.player.ui.PlaylistScreen
import com.cadence.player.ui.PlaylistView
import com.cadence.player.ui.PlaylistsScreen
import com.cadence.player.ui.Selection
import com.cadence.player.ui.SelectionBar
import com.cadence.player.ui.SettingsScreen
import com.cadence.player.ui.TextPromptDialog
import com.cadence.player.ui.TrackActions
import com.cadence.player.ui.rememberPlayerSnapshot
import kotlinx.coroutines.launch

/** Operations that need Android's storage permission prompts, implemented by the activity. */
class StorageActions(
    val deleteTracks: (List<Track>) -> Unit,
    val saveTags: (Track, EditableTags) -> Unit,
)

class MainActivity : ComponentActivity() {
    private val vm: LibraryViewModel by viewModels()
    private var granted by mutableStateOf(false)

    private val audioPermission: String
        get() = if (Build.VERSION.SDK_INT >= 33) Manifest.permission.READ_MEDIA_AUDIO
        else Manifest.permission.READ_EXTERNAL_STORAGE

    private val permissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { result ->
            granted = result[audioPermission] == true
            if (granted) vm.rescan()
        }

    // Result of a system "allow Cadence to modify these files?" prompt.
    private var afterAccess: (() -> Unit)? = null
    private val accessLauncher =
        registerForActivityResult(ActivityResultContracts.StartIntentSenderForResult()) { result ->
            val next = afterAccess
            afterAccess = null
            if (result.resultCode == RESULT_OK) next?.invoke() else toast("Cancelled")
        }

    private val storage = StorageActions(::deleteTracks, ::saveTags)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        granted = ContextCompat.checkSelfPermission(this, audioPermission) == PackageManager.PERMISSION_GRANTED
        if (granted) vm.rescan() else requestPermissions()

        val app = application as CadenceApp
        setContent {
            CadenceTheme {
                CadenceRoot(vm, app.player, app.settings, granted, ::requestPermissions, storage)
            }
        }
    }

    override fun onResume() {
        super.onResume()
        vm.refreshDate()
    }

    private fun requestPermissions() {
        val perms = mutableListOf(audioPermission)
        if (Build.VERSION.SDK_INT >= 33) perms += Manifest.permission.POST_NOTIFICATIONS
        permissionLauncher.launch(perms.toTypedArray())
    }

    private fun toast(message: String) = Toast.makeText(this, message, Toast.LENGTH_SHORT).show()

    private fun launchAccessRequest(sender: IntentSender, then: () -> Unit) {
        afterAccess = then
        accessLauncher.launch(IntentSenderRequest.Builder(sender).build())
    }

    // ---- delete from storage ----

    private fun deleteTracks(tracks: List<Track>) {
        if (tracks.isEmpty()) return
        if (Build.VERSION.SDK_INT >= 30) {
            // One system confirmation covers every file.
            val request = MediaStore.createDeleteRequest(contentResolver, tracks.map { Uri.parse(it.uri) })
            launchAccessRequest(request.intentSender) { vm.onTracksDeleted(tracks) }
        } else {
            deleteLegacy(tracks)
        }
    }

    /** Android 10: delete one by one, asking the user whenever the system says the file isn't ours. */
    private fun deleteLegacy(remaining: List<Track>) {
        val done = ArrayList<Track>()
        for ((i, track) in remaining.withIndex()) {
            try {
                contentResolver.delete(Uri.parse(track.uri), null, null)
                done += track
            } catch (e: RecoverableSecurityException) {
                vm.onTracksDeleted(done)
                launchAccessRequest(e.userAction.actionIntent.intentSender) { deleteLegacy(remaining.drop(i)) }
                return
            } catch (e: SecurityException) {
                toast("Couldn't delete \"${track.title}\"")
            }
        }
        vm.onTracksDeleted(done)
    }

    // ---- edit tags ----

    private fun saveTags(track: Track, tags: EditableTags) {
        val uri = Uri.parse(track.uri)
        val write = {
            lifecycleScope.launch {
                try {
                    TagEditor.write(this@MainActivity, uri, tags)
                    TagEditor.updateIndex(this@MainActivity, uri, tags)
                    vm.onTagsSaved(track, tags)
                    toast("Tags saved")
                } catch (e: RecoverableSecurityException) {
                    launchAccessRequest(e.userAction.actionIntent.intentSender) { saveTags(track, tags) }
                } catch (e: Exception) {
                    toast("Couldn't save tags: ${e.message ?: "unsupported file"}")
                }
            }
            Unit
        }
        if (Build.VERSION.SDK_INT >= 30) {
            val request = MediaStore.createWriteRequest(contentResolver, listOf(uri))
            launchAccessRequest(request.intentSender, write)
        } else {
            write()
        }
    }
}

@Composable
fun CadenceRoot(
    vm: LibraryViewModel,
    player: CadencePlayer,
    settings: Settings,
    hasPermission: Boolean,
    onRequestPermission: () -> Unit,
    storage: StorageActions,
) {
    val snap = rememberPlayerSnapshot(player)
    val albums by vm.albums.collectAsStateWithLifecycle()
    val tracks by vm.tracks.collectAsStateWithLifecycle()
    val playlists by vm.playlists.collectAsStateWithLifecycle()
    val scan by vm.scanState.collectAsStateWithLifecycle()
    val stats by vm.stats.collectAsStateWithLifecycle()
    val bars by vm.dayBars.collectAsStateWithLifecycle()
    val mostPlayed by vm.mostPlayed.collectAsStateWithLifecycle()
    val recentlyPlayed by vm.recentlyPlayed.collectAsStateWithLifecycle()
    val recentlyAdded by vm.recentlyAdded.collectAsStateWithLifecycle()
    val gain by player.appliedGainDb.collectAsStateWithLifecycle()
    val crossfade by settings.crossfadeSeconds.collectAsStateWithLifecycle()
    val shuffleOn by player.shuffleEnabled.collectAsStateWithLifecycle()

    val selection = remember { Selection() }

    var tab by rememberSaveable { mutableIntStateOf(0) } // 0 Home, 1 Library, 2 Playlists, 3 Settings
    var albumId by rememberSaveable { mutableStateOf<Long?>(null) }
    var artistName by rememberSaveable { mutableStateOf<String?>(null) }
    var playlistId by rememberSaveable { mutableStateOf<Long?>(null) }
    var showPlayer by rememberSaveable { mutableStateOf(false) }

    // Dialog state
    var addTo by remember { mutableStateOf<List<Track>?>(null) }
    var deleting by remember { mutableStateOf<List<Track>?>(null) }
    var editing by remember { mutableStateOf<Track?>(null) }
    var playlistDraft by remember { mutableStateOf<Pair<String, List<Track>>?>(null) }
    var newPlaylist by remember { mutableStateOf(false) }
    var renaming by remember { mutableStateOf<PlaylistView?>(null) }
    var deletingPlaylist by remember { mutableStateOf<PlaylistView?>(null) }

    val actions = remember(vm, selection) {
        TrackActions(
            play = { list, i, shuffle -> vm.play(list, i, shuffle) },
            playNext = { vm.playNext(it) },
            addToQueue = { vm.addToQueue(it) },
            addToPlaylist = { addTo = it },
            saveAsPlaylist = { name, list -> playlistDraft = name to list },
            editTags = { editing = it },
            delete = { deleting = it },
            selection = selection,
        )
    }

    LaunchedEffect(tab, albumId, artistName, playlistId) { selection.clear() }
    BackHandler(showPlayer) { showPlayer = false }
    BackHandler(!showPlayer && tab == 1 && artistName != null) { artistName = null }
    BackHandler(!showPlayer && tab == 1 && albumId != null) { albumId = null }
    BackHandler(!showPlayer && tab == 2 && playlistId != null) { playlistId = null }
    BackHandler(selection.active) { selection.clear() }

    Box(Modifier.fillMaxSize()) {
        Scaffold(
            containerColor = MaterialTheme.colorScheme.background,
            bottomBar = {
                Column {
                    if (snap.item != null) MiniPlayer(player, snap) { showPlayer = true }
                    NavigationBar(containerColor = MaterialTheme.colorScheme.surface) {
                        val colors = NavigationBarItemDefaults.colors(
                            selectedIconColor = MaterialTheme.colorScheme.primary,
                            selectedTextColor = MaterialTheme.colorScheme.primary,
                            indicatorColor = MaterialTheme.colorScheme.surfaceVariant,
                        )
                        NavigationBarItem(
                            selected = tab == 0, onClick = { tab = 0 },
                            icon = { Icon(Icons.Rounded.Home, null) }, label = { Text("Home") }, colors = colors,
                        )
                        NavigationBarItem(
                            selected = tab == 1, onClick = { tab = 1; albumId = null; artistName = null },
                            icon = { Icon(Icons.Rounded.LibraryMusic, null) }, label = { Text("Library") }, colors = colors,
                        )
                        NavigationBarItem(
                            selected = tab == 2, onClick = { tab = 2; playlistId = null },
                            icon = { Icon(Icons.AutoMirrored.Rounded.QueueMusic, null) }, label = { Text("Playlists") }, colors = colors,
                        )
                        NavigationBarItem(
                            selected = tab == 3, onClick = { tab = 3 },
                            icon = { Icon(Icons.Rounded.Settings, null) }, label = { Text("Settings") }, colors = colors,
                        )
                    }
                }
            },
        ) { padding ->
            val playingId = snap.item?.mediaId
            when {
                tab == 3 -> SettingsScreen(
                    settings, scan, padding,
                    onRescan = { vm.rescan() },
                    onRereadTags = { vm.rescan(rereadTags = true) },
                    onRebuild = { vm.rescan(rebuild = true) },
                )
                tab == 2 -> {
                    val open = playlists.firstOrNull { it.id == playlistId }
                    if (open != null) {
                        PlaylistScreen(open, playingId, padding, actions, { playlistId = null },
                            { vm.removeFromPlaylist(it) })
                    } else {
                        PlaylistsScreen(
                            playlists, padding,
                            onOpen = { playlistId = it },
                            onCreate = { newPlaylist = true },
                            onRename = { renaming = it },
                            onDelete = { deletingPlaylist = it },
                        )
                    }
                }
                !hasPermission -> EmptyState(
                    "Allow access to your music", "Cadence needs permission to read audio files on this device.",
                    "Allow access", onRequestPermission,
                )
                tracks.isEmpty() -> EmptyState(
                    if (scan.running) "Scanning your music…" else "No music found",
                    if (scan.running) "This only takes a moment." else "Add music files to this device, then scan again.",
                    if (scan.running) null else "Scan again", { vm.rescan() },
                )
                tab == 0 -> HomeScreen(
                    stats, bars, mostPlayed, recentlyPlayed, recentlyAdded, tracks, padding, actions,
                )
                artistName != null -> {
                    ArtistScreen(
                        artist = artistName!!, tracks = tracks, playingId = playingId,
                        padding = padding, actions = actions, onBack = { artistName = null },
                    )
                }
                albumId != null -> {
                    val album = albums.firstOrNull { it.id == albumId }
                    if (album == null) albumId = null else AlbumScreen(
                        album, playingId, padding, actions, onBack = { albumId = null },
                    )
                }
                else -> LibraryScreen(
                    albums, tracks, playingId, padding, actions, settings, vm.libraryUi,
                    onAlbum = { albumId = it },
                    onArtist = { artistName = it },
                )
            }
        }

        AnimatedVisibility(
            visible = selection.active,
            modifier = Modifier.align(Alignment.TopCenter),
            enter = slideInVertically { -it },
            exit = slideOutVertically { -it },
        ) {
            val selected = tracks.filter { it.id in selection.ids }
            SelectionBar(
                count = selection.ids.size,
                onClose = { selection.clear() },
                onSelectAll = { selection.selectAll() },
                onAddToPlaylist = { addTo = selected },
                onAddToQueue = { vm.addToQueue(selected); selection.clear() },
                onDelete = { deleting = selected },
            )
        }

        AnimatedVisibility(
            visible = showPlayer && snap.item != null,
            enter = slideInVertically { it },
            exit = slideOutVertically { it },
        ) {
            NowPlayingScreen(
                player, shuffleOn, gain, crossfade,
                onArtistClick = { artist ->
                    showPlayer = false
                    tab = 1
                    albumId = null
                    artistName = artist
                },
                onClose = { showPlayer = false },
            )
        }
    }

    addTo?.let { list ->
        AddToPlaylistDialog(
            count = list.size, playlists = playlists,
            onDismiss = { addTo = null },
            onPick = { vm.addToPlaylist(it, list); addTo = null; selection.clear() },
            onCreate = { vm.createPlaylist(it, list); addTo = null; selection.clear() },
        )
    }
    deleting?.let { list ->
        val what = if (list.size == 1) "\"${list[0].title}\"" else "${list.size} songs"
        ConfirmDialog(
            title = "Delete $what?",
            message = "This permanently deletes ${if (list.size == 1) "the file" else "the files"} from your device's storage. It can't be undone.",
            confirmLabel = "Delete",
            onDismiss = { deleting = null },
            onConfirm = { storage.deleteTracks(list); deleting = null; selection.clear() },
        )
    }
    editing?.let { track ->
        EditTagsDialog(track, onDismiss = { editing = null }, onSave = { storage.saveTags(track, it); editing = null })
    }
    playlistDraft?.let { (name, list) ->
        TextPromptDialog(
            "Save as playlist", name, "Save",
            onDismiss = { playlistDraft = null },
            onConfirm = { vm.createPlaylist(it, list); playlistDraft = null },
        )
    }
    if (newPlaylist) {
        TextPromptDialog(
            "New playlist", "", "Create",
            onDismiss = { newPlaylist = false },
            onConfirm = { vm.createPlaylist(it, emptyList()); newPlaylist = false },
        )
    }
    renaming?.let { p ->
        TextPromptDialog(
            "Rename playlist", p.name, "Rename",
            onDismiss = { renaming = null },
            onConfirm = { vm.renamePlaylist(p.id, it); renaming = null },
        )
    }
    deletingPlaylist?.let { p ->
        ConfirmDialog(
            title = "Delete \"${p.name}\"?",
            message = "The songs themselves stay on your device.",
            confirmLabel = "Delete",
            onDismiss = { deletingPlaylist = null },
            onConfirm = {
                vm.deletePlaylist(p.id)
                if (playlistId == p.id) playlistId = null
                deletingPlaylist = null
            },
        )
    }
}

@Composable
private fun EmptyState(title: String, body: String, action: String?, onAction: () -> Unit) {
    Column(
        Modifier.fillMaxSize().padding(32.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(title, fontSize = 22.sp, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center)
        Spacer(Modifier.size(8.dp))
        Text(body, color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center)
        if (action != null) {
            Spacer(Modifier.size(20.dp))
            Button(onClick = onAction) { Text(action) }
        }
    }
}

@Composable
private fun MiniPlayer(player: Player, snap: PlayerSnapshot, onOpen: () -> Unit) {
    val item = snap.item ?: return
    Surface(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 6.dp).clickable(onClick = onOpen),
        shape = RoundedCornerShape(14.dp),
        color = MaterialTheme.colorScheme.surfaceVariant,
    ) {
        Row(Modifier.padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
            Artwork(item.localConfiguration?.uri?.toString(), Modifier.size(44.dp), px = 128, corner = 6.dp)
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    item.mediaMetadata.title?.toString().orEmpty(),
                    maxLines = 1, overflow = TextOverflow.Ellipsis, fontWeight = FontWeight.Medium,
                )
                Text(
                    item.mediaMetadata.artist?.toString().orEmpty(),
                    maxLines = 1, overflow = TextOverflow.Ellipsis, fontSize = 13.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            IconButton(onClick = { if (snap.playWhenReady) player.pause() else player.play() }) {
                Icon(if (snap.playWhenReady) Icons.Rounded.Pause else Icons.Rounded.PlayArrow, "Play or pause")
            }
            IconButton(onClick = { player.seekToNext() }) { Icon(Icons.Rounded.SkipNext, "Next") }
        }
    }
}
