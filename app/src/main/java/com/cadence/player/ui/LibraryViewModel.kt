package com.cadence.player.ui

import android.app.Application
import android.content.Intent
import android.widget.Toast
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.cadence.player.CadenceApp
import com.cadence.player.data.EditableTags
import com.cadence.player.data.ScanState
import com.cadence.player.data.Track
import com.cadence.player.playback.PlaybackService
import com.cadence.player.playback.SecureShuffle
import com.cadence.player.playback.toMediaItem
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.LocalDate

/** Library tab state kept in the ViewModel so it survives opening an album and coming back. */
class LibraryUiState {
    var section by mutableIntStateOf(0)
    var query by mutableStateOf("")
    var folderPath by mutableStateOf("")
}

data class Album(val id: Long, val title: String, val artist: String, val tracks: List<Track>)

data class PlaylistItem(val entryId: Long, val track: Track)

data class PlaylistView(val id: Long, val name: String, val items: List<PlaylistItem>) {
    val tracks: List<Track> get() = items.map { it.track }
}

data class ListeningStats(val today: Long = 0, val week: Long = 0, val month: Long = 0, val lifetime: Long = 0)

data class DayBar(val day: LocalDate, val ms: Long)

data class PlayedTrack(val track: Track, val plays: Int, val listenedMs: Long)

@OptIn(FlowPreview::class, ExperimentalCoroutinesApi::class)
class LibraryViewModel(app: Application) : AndroidViewModel(app) {
    private val cadence = app as CadenceApp
    val libraryUi = LibraryUiState()
    private val userDao = cadence.userDb.user()
    private val trackDao = cadence.db.tracks()

    // Debounced: tag scanning updates rows continuously and we don't want a recomposition per file.
    val tracks: StateFlow<List<Track>> = trackDao.observeAll()
        .debounce(400)
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    val albums: StateFlow<List<Album>> = tracks
        .map { list ->
            list.groupBy { it.albumId }.map { (id, t) ->
                val sorted = t.sortedWith(compareBy({ it.discNumber }, { it.trackNumber }, { it.title }))
                val artists = t.map { it.artist }.distinct()
                Album(id, t.first().album, if (artists.size == 1) artists[0] else "Various Artists", sorted)
            }.sortedBy { it.title.lowercase() }
        }
        .flowOn(Dispatchers.Default)
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    val playlists: StateFlow<List<PlaylistView>> =
        combine(userDao.observePlaylists(), userDao.observeEntries(), tracks) { lists, entries, library ->
            val byId = library.associateBy { it.id }
            val grouped = entries.groupBy { it.playlistId }
            lists.map { p ->
                PlaylistView(p.id, p.name, grouped[p.id].orEmpty().mapNotNull { e ->
                    byId[e.trackId]?.let { PlaylistItem(e.entryId, it) }
                })
            }
        }.flowOn(Dispatchers.Default).stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    // ---- home / stats ----

    private val today = MutableStateFlow(LocalDate.now())

    /** Call when the app returns to the foreground so "today" and the rolling windows move past midnight. */
    fun refreshDate() {
        today.value = LocalDate.now()
    }

    val stats: StateFlow<ListeningStats> = today.flatMapLatest { d ->
        combine(
            userDao.observeSince(d.toString()),
            userDao.observeSince(d.minusDays(6).toString()),
            userDao.observeSince(d.minusDays(29).toString()),
            userDao.observeSince("0000-00-00"),
        ) { t, w, m, l -> ListeningStats(t, w, m, l) }
    }.stateIn(viewModelScope, SharingStarted.Eagerly, ListeningStats())

    val dayBars: StateFlow<List<DayBar>> = today.flatMapLatest { d ->
        userDao.observeDays(d.minusDays(6).toString()).map { rows ->
            val byDay = rows.associate { it.day to it.ms }
            (6 downTo 0).map { k ->
                val day = d.minusDays(k.toLong())
                DayBar(day, byDay[day.toString()] ?: 0L)
            }
        }
    }.stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    val mostPlayed: StateFlow<List<PlayedTrack>> =
        combine(userDao.observeMostPlayed(), tracks) { rows, library ->
            val byId = library.associateBy { it.id }
            rows.mapNotNull { s -> byId[s.trackId]?.let { PlayedTrack(it, s.plays, s.listenedMs) } }.take(10)
        }.stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    val recentlyPlayed: StateFlow<List<Track>> =
        combine(userDao.observeRecent(), tracks) { rows, library ->
            val byId = library.associateBy { it.id }
            rows.mapNotNull { byId[it.trackId] }.take(15)
        }.stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    val recentlyAdded: StateFlow<List<Track>> = tracks
        .map { list -> list.sortedByDescending { it.dateModified }.take(15) }
        .flowOn(Dispatchers.Default)
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    // ---- library ----

    val scanState: StateFlow<ScanState> = cadence.scanner.state

    fun rescan(rereadTags: Boolean = false, rebuild: Boolean = false) {
        viewModelScope.launch { cadence.scanner.scan(rereadTags, rebuild) }
    }

    // ---- playback ----

    /**
     * Replaces the queue with [list] and starts playing.
     *  - [shuffle] = true (a Shuffle button): starts on a random track and the rest follow in random order.
     *  - Shuffle already switched on: the tapped track plays first, everything else is shuffled behind it.
     */
    fun play(list: List<Track>, startIndex: Int, shuffle: Boolean = false) {
        if (list.isEmpty()) return
        viewModelScope.launch {
            val player = cadence.player
            val shuffled = shuffle || player.shuffleEnabled.value
            val start = if (shuffle) SecureShuffle.randomIndex(list.size) else startIndex
            val mediaItems = withContext(Dispatchers.Default) { list.map { it.toMediaItem() } }
            val ctx = getApplication<Application>()
            ctx.startService(Intent(ctx, PlaybackService::class.java))
            player.setMediaItems(mediaItems, start, 0L)
            if (shuffled) player.setShuffle(true)
            player.prepare()
            player.play()
        }
    }

    fun playNext(tracks: List<Track>) = enqueue(tracks, next = true)

    fun addToQueue(tracks: List<Track>) = enqueue(tracks, next = false)

    private fun enqueue(list: List<Track>, next: Boolean) {
        if (list.isEmpty()) return
        val player = cadence.player
        if (player.mediaItemCount == 0) {
            play(list, 0)
            return
        }
        val at = if (next) player.currentMediaItemIndex + 1 else player.mediaItemCount
        player.addMediaItems(at, list.map { it.toMediaItem() })
        toast(
            (if (next) "Playing next" else "Added to queue") + if (list.size > 1) " (${list.size} songs)" else ""
        )
    }

    // ---- playlists ----

    fun createPlaylist(name: String, list: List<Track>) {
        viewModelScope.launch {
            val clean = name.trim().ifBlank { "New playlist" }
            userDao.createPlaylist(clean, list.map { it.id })
            toast(if (list.isEmpty()) "Created \"$clean\"" else "Created \"$clean\" with ${list.size} songs")
        }
    }

    fun addToPlaylist(playlist: PlaylistView, list: List<Track>) {
        if (list.isEmpty()) return
        viewModelScope.launch {
            userDao.addTracks(playlist.id, list.map { it.id })
            toast("Added ${list.size} to \"${playlist.name}\"")
        }
    }

    fun removeFromPlaylist(entryId: Long) {
        viewModelScope.launch { userDao.removeEntry(entryId) }
    }

    fun renamePlaylist(id: Long, name: String) {
        viewModelScope.launch { userDao.rename(id, name.trim()) }
    }

    fun deletePlaylist(id: Long) {
        viewModelScope.launch { userDao.deletePlaylist(id) }
    }

    // ---- storage changes (the activity does the system permission dance, then calls these) ----

    fun onTracksDeleted(deleted: List<Track>) {
        if (deleted.isEmpty()) return
        val ids = deleted.map { it.id }
        val keys = ids.mapTo(HashSet()) { it.toString() }
        val player = cadence.player
        for (i in player.mediaItemCount - 1 downTo 0) {
            if (player.getMediaItemAt(i).mediaId in keys) player.removeMediaItem(i)
        }
        viewModelScope.launch {
            ids.chunked(500).forEach { trackDao.delete(it) }
            userDao.purgeTracks(ids)
        }
        toast(if (deleted.size == 1) "Deleted \"${deleted[0].title}\"" else "Deleted ${deleted.size} songs")
    }

    fun onTagsSaved(track: Track, tags: EditableTags) {
        viewModelScope.launch {
            trackDao.updateMeta(
                id = track.id,
                title = tags.title.trim().ifBlank { track.title },
                artist = tags.artist.trim().ifBlank { "Unknown Artist" },
                album = tags.album.trim().ifBlank { "Unknown Album" },
                year = tags.year.trim().toIntOrNull() ?: track.year,
                trackNumber = tags.track.substringBefore('/').trim().toIntOrNull() ?: track.trackNumber,
            )
            cadence.scanner.scan() // refreshes the row (album id, ReplayGain) from MediaStore
        }
    }

    fun toast(message: String) {
        Toast.makeText(getApplication(), message, Toast.LENGTH_SHORT).show()
    }
}
