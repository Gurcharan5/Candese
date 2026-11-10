package com.cadence.player.data

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.provider.MediaStore
import android.provider.OpenableColumns
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.jaudiotagger.audio.AudioFileIO
import org.jaudiotagger.tag.FieldKey
import java.io.File
import java.util.logging.Level
import java.util.logging.Logger

data class EditableTags(
    val title: String = "",
    val artist: String = "",
    val album: String = "",
    val albumArtist: String = "",
    val genre: String = "",
    val year: String = "",
    val track: String = "",
)

/**
 * Reads and writes tags with jaudiotagger (MP3, FLAC, Ogg, Opus, M4A, WAV...).
 * jaudiotagger needs a real file, so the audio is copied to the cache, edited there, and streamed back
 * into the original through the content resolver. The caller must already hold write access to [uri].
 */
object TagEditor {
    private fun configure() {
        Logger.getLogger("org.jaudiotagger").level = Level.OFF
    }

    private fun extension(context: Context, uri: Uri): String {
        context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use {
            if (it.moveToFirst()) return it.getString(0).substringAfterLast('.', "mp3")
        }
        return "mp3"
    }

    private fun copyToTemp(context: Context, uri: Uri): File {
        val file = File.createTempFile("tag", ".${extension(context, uri)}", context.cacheDir)
        context.contentResolver.openInputStream(uri)!!.use { input ->
            file.outputStream().use { input.copyTo(it) }
        }
        return file
    }

    suspend fun read(context: Context, uri: Uri): EditableTags = withContext(Dispatchers.IO) {
        configure()
        val file = copyToTemp(context, uri)
        try {
            val tag = AudioFileIO.read(file).tag
            fun get(key: FieldKey): String = runCatching { tag?.getFirst(key) }.getOrNull().orEmpty()
            EditableTags(
                title = get(FieldKey.TITLE),
                artist = get(FieldKey.ARTIST),
                album = get(FieldKey.ALBUM),
                albumArtist = get(FieldKey.ALBUM_ARTIST),
                genre = get(FieldKey.GENRE),
                year = get(FieldKey.YEAR),
                track = get(FieldKey.TRACK),
            )
        } finally {
            file.delete()
        }
    }

    suspend fun write(context: Context, uri: Uri, tags: EditableTags) = withContext(Dispatchers.IO) {
        configure()
        val file = copyToTemp(context, uri)
        try {
            val audio = AudioFileIO.read(file)
            val tag = audio.tagOrCreateAndSetDefault
            fun set(key: FieldKey, value: String) {
                runCatching { if (value.isBlank()) tag.deleteField(key) else tag.setField(key, value.trim()) }
            }
            set(FieldKey.TITLE, tags.title)
            set(FieldKey.ARTIST, tags.artist)
            set(FieldKey.ALBUM, tags.album)
            set(FieldKey.ALBUM_ARTIST, tags.albumArtist)
            set(FieldKey.GENRE, tags.genre)
            set(FieldKey.YEAR, tags.year)
            set(FieldKey.TRACK, tags.track)
            audio.commit()
            context.contentResolver.openOutputStream(uri, "wt")!!.use { out ->
                file.inputStream().use { it.copyTo(out) }
            }
        } finally {
            file.delete()
        }
    }

    /** Keeps MediaStore's own index in step so the change shows up immediately. */
    fun updateIndex(context: Context, uri: Uri, tags: EditableTags) {
        val values = ContentValues().apply {
            if (tags.title.isNotBlank()) put(MediaStore.Audio.Media.TITLE, tags.title.trim())
            put(MediaStore.Audio.Media.ARTIST, tags.artist.trim())
            put(MediaStore.Audio.Media.ALBUM, tags.album.trim())
            tags.year.trim().toIntOrNull()?.let { put(MediaStore.Audio.Media.YEAR, it) }
            tags.track.substringBefore('/').trim().toIntOrNull()?.let { put(MediaStore.Audio.Media.TRACK, it) }
        }
        runCatching { context.contentResolver.update(uri, values, null, null) }
    }
}
