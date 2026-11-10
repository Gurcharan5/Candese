package com.cadence.player.data

import android.content.ContentUris
import android.content.Context
import android.net.Uri
import android.provider.MediaStore
import androidx.media3.common.MediaItem
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.MetadataRetriever
import androidx.media3.extractor.metadata.id3.InternalFrame
import androidx.media3.extractor.metadata.id3.TextInformationFrame
import androidx.media3.extractor.metadata.vorbis.VorbisComment
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.guava.await
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

data class ScanState(val running: Boolean = false, val done: Int = 0, val total: Int = 0)

data class RgTags(
    val trackGain: Float?,
    val albumGain: Float?,
    val trackPeak: Float?,
    val albumPeak: Float?,
)

/**
 * Two passes:
 *  1. Sync the Room table with MediaStore (fast, only touches new/modified files).
 *  2. Read ReplayGain tags for files that haven't been tag-scanned yet (slower, parallel).
 */
class LibraryScanner(
    private val context: Context,
    private val dao: TrackDao,
    private val settings: Settings,
) {
    private val analysisScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val analyzing = AtomicBoolean(false)
    private val _analysis = MutableStateFlow(ScanState())

    /** Progress of the background loudness measurement for untagged songs. */
    val analysis: StateFlow<ScanState> = _analysis

    private val running = AtomicBoolean(false)
    private val _state = MutableStateFlow(ScanState())
    val state: StateFlow<ScanState> = _state

    suspend fun scan(rereadTags: Boolean = false, rebuild: Boolean = false) = withContext(Dispatchers.IO) {
        if (!running.compareAndSet(false, true)) return@withContext
        try {
            _state.value = ScanState(running = true)
            if (rebuild) dao.clear()
            if (rereadTags) dao.invalidateTags()

            val existing = dao.stamps().associate { it.id to it.dateModified }
            val found = queryMediaStore()

            val changed = found.filter { existing[it.id] != it.dateModified }
            if (changed.isNotEmpty()) dao.upsert(changed)

            val foundIds = found.mapTo(HashSet()) { it.id }
            existing.keys.filter { it !in foundIds }.chunked(500).forEach { dao.delete(it) }

            val pending = dao.unscanned()
            _state.value = ScanState(true, 0, pending.size)
            val done = AtomicInteger(0)
            val gate = Semaphore(4)
            coroutineScope {
                pending.map { t ->
                    async {
                        gate.withPermit {
                            val tags = runCatching { TagReader.read(context, Uri.parse(t.uri)) }.getOrNull()
                            if (tags != null && (tags.trackGain != null || tags.albumGain != null)) {
                                dao.setGain(t.id, tags.trackGain, tags.albumGain, tags.trackPeak, tags.albumPeak)
                            } else {
                                dao.markScanned(t.id)
                            }
                            _state.value = ScanState(true, done.incrementAndGet(), pending.size)
                        }
                    }
                }.awaitAll()
            }
        } finally {
            _state.value = ScanState(running = false)
            running.set(false)
        }
        if (settings.analyzeUntagged.value) launchAnalysis()
    }

    /** Measures loudness (EBU R128 style) for songs without ReplayGain tags, in the background. */
    fun launchAnalysis() {
        if (!analyzing.compareAndSet(false, true)) return
        analysisScope.launch {
            try {
                val pending = dao.unmeasured()
                _analysis.value = ScanState(true, 0, pending.size)
                val done = AtomicInteger(0)
                val gate = Semaphore(2)
                coroutineScope {
                    pending.map { t ->
                        async {
                            gate.withPermit {
                                if (!settings.analyzeUntagged.value) return@withPermit
                                val m = LoudnessAnalyzer.measure(context, Uri.parse(t.uri))
                                dao.setMeasured(t.id, m?.gainDb, m?.peak)
                                _analysis.value = ScanState(true, done.incrementAndGet(), pending.size)
                            }
                        }
                    }.awaitAll()
                }
            } finally {
                _analysis.value = ScanState()
                analyzing.set(false)
            }
        }
    }

    private fun queryMediaStore(): List<Track> {
        val out = ArrayList<Track>()
        val collection = MediaStore.Audio.Media.EXTERNAL_CONTENT_URI
        val projection = arrayOf(
            MediaStore.Audio.Media._ID,
            MediaStore.Audio.Media.TITLE,
            MediaStore.Audio.Media.ARTIST,
            MediaStore.Audio.Media.ALBUM,
            MediaStore.Audio.Media.ALBUM_ID,
            MediaStore.Audio.Media.DURATION,
            MediaStore.Audio.Media.TRACK,
            MediaStore.Audio.Media.YEAR,
            MediaStore.Audio.Media.DATE_MODIFIED,
            MediaStore.Audio.Media.RELATIVE_PATH,
            MediaStore.Audio.Media.DISPLAY_NAME,
        )
        context.contentResolver.query(
            collection, projection, "${MediaStore.Audio.Media.IS_MUSIC} != 0", null, null
        )?.use { c ->
            val iId = c.getColumnIndexOrThrow(MediaStore.Audio.Media._ID)
            val iTitle = c.getColumnIndexOrThrow(MediaStore.Audio.Media.TITLE)
            val iArtist = c.getColumnIndexOrThrow(MediaStore.Audio.Media.ARTIST)
            val iAlbum = c.getColumnIndexOrThrow(MediaStore.Audio.Media.ALBUM)
            val iAlbumId = c.getColumnIndexOrThrow(MediaStore.Audio.Media.ALBUM_ID)
            val iDur = c.getColumnIndexOrThrow(MediaStore.Audio.Media.DURATION)
            val iTrack = c.getColumnIndexOrThrow(MediaStore.Audio.Media.TRACK)
            val iYear = c.getColumnIndexOrThrow(MediaStore.Audio.Media.YEAR)
            val iMod = c.getColumnIndexOrThrow(MediaStore.Audio.Media.DATE_MODIFIED)
            val iPath = c.getColumnIndexOrThrow(MediaStore.Audio.Media.RELATIVE_PATH)
            val iName = c.getColumnIndexOrThrow(MediaStore.Audio.Media.DISPLAY_NAME)
            while (c.moveToNext()) {
                val id = c.getLong(iId)
                val rawTrack = c.getInt(iTrack) // e.g. 1002 = disc 1, track 2
                out += Track(
                    id = id,
                    uri = ContentUris.withAppendedId(collection, id).toString(),
                    title = c.getString(iTitle) ?: "Unknown",
                    artist = c.getString(iArtist)?.takeUnless { it == "<unknown>" } ?: "Unknown Artist",
                    album = c.getString(iAlbum) ?: "Unknown Album",
                    albumId = c.getLong(iAlbumId),
                    durationMs = c.getLong(iDur),
                    trackNumber = rawTrack % 1000,
                    discNumber = (rawTrack / 1000).coerceAtLeast(1),
                    year = c.getInt(iYear),
                    dateModified = c.getLong(iMod),
                    folder = c.getString(iPath).orEmpty(),
                    fileName = c.getString(iName).orEmpty(),
                )
            }
        }
        return out
    }
}

/** Reads ReplayGain tags from ID3 (TXXX), Vorbis comments (FLAC/Ogg/Opus) and MP4 freeform atoms. */
@OptIn(UnstableApi::class)
object TagReader {
    private val NUMBER = Regex("""[-+]?\d+(?:[.,]\d+)?""")

    suspend fun read(context: Context, uri: Uri): RgTags {
        val groups = MetadataRetriever.retrieveMetadata(context, MediaItem.fromUri(uri)).await()
        val tags = HashMap<String, String>()
        for (g in 0 until groups.length) {
            val group = groups.get(g)
            for (f in 0 until group.length) {
                val md = group.getFormat(f).metadata ?: continue
                for (i in 0 until md.length()) {
                    when (val e = md.get(i)) {
                        is TextInformationFrame -> {
                            val desc = e.description
                            val value = e.values.firstOrNull()
                            if (e.id == "TXXX" && desc != null && value != null) tags[desc.uppercase()] = value
                        }
                        is VorbisComment -> tags[e.key.uppercase()] = e.value
                        is InternalFrame -> tags[e.description.uppercase()] = e.text
                        else -> Unit
                    }
                }
            }
        }

        var trackGain = parseDb(tags["REPLAYGAIN_TRACK_GAIN"])
        var albumGain = parseDb(tags["REPLAYGAIN_ALBUM_GAIN"])
        // Opus uses R128_* tags: Q7.8 fixed point relative to -23 LUFS. ReplayGain 2 reference is -18 LUFS (+5 dB).
        if (trackGain == null) parseR128(tags["R128_TRACK_GAIN"])?.let { trackGain = it }
        if (albumGain == null) parseR128(tags["R128_ALBUM_GAIN"])?.let { albumGain = it }

        return RgTags(
            trackGain = trackGain,
            albumGain = albumGain,
            trackPeak = parsePeak(tags["REPLAYGAIN_TRACK_PEAK"]),
            albumPeak = parsePeak(tags["REPLAYGAIN_ALBUM_PEAK"]),
        )
    }

    private fun parseDb(s: String?): Float? =
        s?.let { NUMBER.find(it)?.value?.replace(',', '.')?.toFloatOrNull() }

    private fun parsePeak(s: String?): Float? =
        s?.trim()?.replace(',', '.')?.toFloatOrNull()

    private fun parseR128(s: String?): Float? =
        s?.trim()?.toIntOrNull()?.let { it / 256f + 5f }
}
