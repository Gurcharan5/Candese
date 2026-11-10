package com.cadence.player.data

import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.RoomDatabase
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow

@Entity(tableName = "tracks")
data class Track(
    @PrimaryKey val id: Long,          // MediaStore _ID
    val uri: String,
    val title: String,
    val artist: String,
    val album: String,
    val albumId: Long,
    val durationMs: Long,
    val trackNumber: Int,
    val discNumber: Int,
    val year: Int,
    val dateModified: Long,
    /** MediaStore RELATIVE_PATH, e.g. "Music/Rock/" (empty if unknown). */
    val folder: String = "",
    // ReplayGain tags (dB / linear peak). Null = not tagged.
    val rgTrackGain: Float? = null,
    val rgAlbumGain: Float? = null,
    val rgTrackPeak: Float? = null,
    val rgAlbumPeak: Float? = null,
    val tagsScanned: Boolean = false,
    /** File name inside [folder], used for M3U playlists. */
    val fileName: String = "",
    /** True when rgTrackGain was measured by Cadence because the file has no ReplayGain tags. */
    val rgMeasured: Boolean = false,
)

data class Stamp(val id: Long, val dateModified: Long)

@Dao
interface TrackDao {
    @Query("SELECT * FROM tracks ORDER BY title COLLATE NOCASE")
    fun observeAll(): Flow<List<Track>>

    @Query("SELECT id, dateModified FROM tracks")
    suspend fun stamps(): List<Stamp>

    @Upsert
    suspend fun upsert(tracks: List<Track>)

    @Query("DELETE FROM tracks WHERE id IN (:ids)")
    suspend fun delete(ids: List<Long>)

    @Query("SELECT * FROM tracks WHERE tagsScanned = 0")
    suspend fun unscanned(): List<Track>

    @Query("UPDATE tracks SET tagsScanned = 0")
    suspend fun invalidateTags()

    @Query("DELETE FROM tracks")
    suspend fun clear()

    /** Quick local update after a tag edit; dateModified = -1 makes the next scan refresh the row from MediaStore. */
    @Query(
        "UPDATE tracks SET title = :title, artist = :artist, album = :album, year = :year, " +
            "trackNumber = :trackNumber, dateModified = -1 WHERE id = :id"
    )
    suspend fun updateMeta(id: Long, title: String, artist: String, album: String, year: Int, trackNumber: Int)

    @Query(
        "UPDATE tracks SET rgTrackGain = :tg, rgAlbumGain = :ag, rgTrackPeak = :tp, " +
            "rgAlbumPeak = :ap, rgMeasured = 0, tagsScanned = 1 WHERE id = :id"
    )
    suspend fun setGain(id: Long, tg: Float?, ag: Float?, tp: Float?, ap: Float?)

    /** Untagged file: just mark it read, keeping any loudness we measured earlier. */
    @Query("UPDATE tracks SET tagsScanned = 1 WHERE id = :id")
    suspend fun markScanned(id: Long)

    @Query("SELECT * FROM tracks WHERE tagsScanned = 1 AND rgTrackGain IS NULL AND rgAlbumGain IS NULL AND rgMeasured = 0")
    suspend fun unmeasured(): List<Track>

    @Query("UPDATE tracks SET rgTrackGain = :gain, rgTrackPeak = :peak, rgMeasured = 1 WHERE id = :id")
    suspend fun setMeasured(id: Long, gain: Float?, peak: Float?)

    @Query("SELECT * FROM tracks ORDER BY title COLLATE NOCASE")
    suspend fun all(): List<Track>
}

@Database(entities = [Track::class], version = 3, exportSchema = false)
abstract class AppDatabase : RoomDatabase() {
    abstract fun tracks(): TrackDao
}
