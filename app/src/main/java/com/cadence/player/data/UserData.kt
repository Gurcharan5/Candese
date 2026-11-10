package com.cadence.player.data

import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.Index
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.RoomDatabase
import androidx.room.Transaction
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import kotlinx.coroutines.flow.Flow

@Entity(tableName = "playlists")
data class Playlist(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    val createdAt: Long,
)

/** trackId is the MediaStore id, so entries survive library rebuilds. */
@Entity(tableName = "playlist_entries", indices = [Index("playlistId")])
data class PlaylistEntry(
    @PrimaryKey(autoGenerate = true) val entryId: Long = 0,
    val playlistId: Long,
    val trackId: Long,
    val position: Int,
)

/** Listening time per local calendar day, day = ISO date such as "2026-11-02". */
@Entity(tableName = "daily_listening")
data class DailyListening(@PrimaryKey val day: String, val ms: Long)

@Entity(tableName = "track_stats")
data class TrackStats(
    @PrimaryKey val trackId: Long,
    val plays: Int,
    val listenedMs: Long,
    val lastPlayed: Long,
)

/** 1 to 5 stars. No row means unrated. */
@Entity(tableName = "track_ratings")
data class TrackRating(@PrimaryKey val trackId: Long, val rating: Int)

/** Plays and listening time per track per day, for the monthly and yearly top lists. */
@Entity(tableName = "daily_track_stats", primaryKeys = ["day", "trackId"])
data class DailyTrackStat(val day: String, val trackId: Long, val plays: Int, val ms: Long)

data class TrackStatDelta(val trackId: Long, val plays: Int, val ms: Long)

data class TopRow(val trackId: Long, val plays: Int, val listenedMs: Long)

@Dao
abstract class UserDao {
    // ---- playlists ----
    @Query("SELECT * FROM playlists ORDER BY name COLLATE NOCASE")
    abstract fun observePlaylists(): Flow<List<Playlist>>

    @Query("SELECT * FROM playlist_entries ORDER BY playlistId, position")
    abstract fun observeEntries(): Flow<List<PlaylistEntry>>

    @Insert
    abstract suspend fun insertPlaylist(p: Playlist): Long

    @Insert
    abstract suspend fun insertEntries(entries: List<PlaylistEntry>)

    @Query("SELECT COALESCE(MAX(position), -1) FROM playlist_entries WHERE playlistId = :id")
    abstract suspend fun maxPosition(id: Long): Int

    @Query("DELETE FROM playlist_entries WHERE entryId = :entryId")
    abstract suspend fun removeEntry(entryId: Long)

    @Query("DELETE FROM playlist_entries WHERE playlistId = :id")
    abstract suspend fun clearPlaylist(id: Long)

    @Query("DELETE FROM playlists WHERE id = :id")
    abstract suspend fun deletePlaylistRow(id: Long)

    @Query("UPDATE playlists SET name = :name WHERE id = :id")
    abstract suspend fun rename(id: Long, name: String)

    @Query("DELETE FROM playlist_entries WHERE trackId IN (:ids)")
    abstract suspend fun removeTracks(ids: List<Long>)

    @Query("DELETE FROM track_stats WHERE trackId IN (:ids)")
    abstract suspend fun removeStats(ids: List<Long>)

    @Transaction
    open suspend fun createPlaylist(name: String, trackIds: List<Long>): Long {
        val id = insertPlaylist(Playlist(name = name, createdAt = System.currentTimeMillis()))
        insertEntries(trackIds.mapIndexed { i, t -> PlaylistEntry(playlistId = id, trackId = t, position = i) })
        return id
    }

    @Transaction
    open suspend fun addTracks(playlistId: Long, trackIds: List<Long>) {
        val start = maxPosition(playlistId) + 1
        insertEntries(trackIds.mapIndexed { i, t -> PlaylistEntry(playlistId = playlistId, trackId = t, position = start + i) })
    }

    @Transaction
    open suspend fun deletePlaylist(id: Long) {
        clearPlaylist(id)
        deletePlaylistRow(id)
    }

    /** Called after songs are deleted from storage. */
    @Transaction
    open suspend fun purgeTracks(ids: List<Long>) {
        ids.chunked(500).forEach {
            removeTracks(it)
            removeStats(it)
            removeRatings(it)
            removeDailyTrackStats(it)
        }
    }

    @Query("DELETE FROM track_ratings WHERE trackId IN (:ids)")
    abstract suspend fun removeRatings(ids: List<Long>)

    @Query("DELETE FROM daily_track_stats WHERE trackId IN (:ids)")
    abstract suspend fun removeDailyTrackStats(ids: List<Long>)

    // ---- ratings ----
    @Query("SELECT * FROM track_ratings")
    abstract fun observeRatings(): Flow<List<TrackRating>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    abstract suspend fun setRating(r: TrackRating)

    @Query("DELETE FROM track_ratings WHERE trackId = :id")
    abstract suspend fun clearRating(id: Long)

    // ---- one-shot reads for Android Auto ----
    @Query("SELECT * FROM playlists ORDER BY name COLLATE NOCASE")
    abstract suspend fun playlistsOnce(): List<Playlist>

    @Query("SELECT * FROM playlist_entries WHERE playlistId = :id ORDER BY position")
    abstract suspend fun entriesOf(id: Long): List<PlaylistEntry>

    @Query("SELECT trackId, plays, listenedMs FROM track_stats WHERE plays > 0 ORDER BY plays DESC, listenedMs DESC LIMIT 50")
    abstract suspend fun topLifetime(): List<TopRow>

    @Query(
        "SELECT trackId, SUM(plays) AS plays, SUM(ms) AS listenedMs FROM daily_track_stats WHERE day >= :fromDay " +
            "GROUP BY trackId HAVING SUM(plays) > 0 ORDER BY SUM(plays) DESC, SUM(ms) DESC LIMIT 50"
    )
    abstract suspend fun topSince(fromDay: String): List<TopRow>

    // ---- top 50 lists ----
    @Query("SELECT trackId, plays, listenedMs FROM track_stats WHERE plays > 0 ORDER BY plays DESC, listenedMs DESC LIMIT 50")
    abstract fun observeTopLifetime(): Flow<List<TopRow>>

    @Query(
        "SELECT trackId, SUM(plays) AS plays, SUM(ms) AS listenedMs FROM daily_track_stats WHERE day >= :fromDay " +
            "GROUP BY trackId HAVING SUM(plays) > 0 ORDER BY SUM(plays) DESC, SUM(ms) DESC LIMIT 50"
    )
    abstract fun observeTopSince(fromDay: String): Flow<List<TopRow>>

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    abstract suspend fun insertDailyTrack(d: DailyTrackStat): Long

    @Query("UPDATE daily_track_stats SET plays = plays + :plays, ms = ms + :ms WHERE day = :day AND trackId = :id")
    abstract suspend fun bumpDailyTrack(day: String, id: Long, plays: Int, ms: Long)

    // ---- listening stats ----
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    abstract suspend fun insertDay(d: DailyListening): Long

    @Query("UPDATE daily_listening SET ms = ms + :ms WHERE day = :day")
    abstract suspend fun addDay(day: String, ms: Long)

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    abstract suspend fun insertTrackStat(s: TrackStats): Long

    @Query("UPDATE track_stats SET plays = plays + :plays, listenedMs = listenedMs + :ms, lastPlayed = :now WHERE trackId = :id")
    abstract suspend fun bumpTrack(id: Long, plays: Int, ms: Long, now: Long)

    @Transaction
    open suspend fun record(day: String, ms: Long, rows: List<TrackStatDelta>, now: Long) {
        if (ms > 0) {
            insertDay(DailyListening(day, 0))
            addDay(day, ms)
        }
        for (r in rows) {
            insertTrackStat(TrackStats(r.trackId, 0, 0, 0))
            bumpTrack(r.trackId, r.plays, r.ms, now)
            insertDailyTrack(DailyTrackStat(day, r.trackId, 0, 0))
            bumpDailyTrack(day, r.trackId, r.plays, r.ms)
        }
    }

    @Query("SELECT COALESCE(SUM(ms), 0) FROM daily_listening WHERE day >= :fromDay")
    abstract fun observeSince(fromDay: String): Flow<Long>

    @Query("SELECT * FROM daily_listening WHERE day >= :fromDay ORDER BY day")
    abstract fun observeDays(fromDay: String): Flow<List<DailyListening>>

    @Query("SELECT * FROM track_stats WHERE plays > 0 ORDER BY plays DESC, listenedMs DESC LIMIT 30")
    abstract fun observeMostPlayed(): Flow<List<TrackStats>>

    @Query("SELECT * FROM track_stats WHERE lastPlayed > 0 ORDER BY lastPlayed DESC LIMIT 30")
    abstract fun observeRecent(): Flow<List<TrackStats>>
}

@Database(
    entities = [
        Playlist::class, PlaylistEntry::class, DailyListening::class, TrackStats::class,
        TrackRating::class, DailyTrackStat::class,
    ],
    version = 2,
    exportSchema = false,
)
abstract class UserDatabase : RoomDatabase() {
    abstract fun user(): UserDao
}

/** Adds star ratings and per-day track stats (for the monthly / yearly top 50). Existing playlists and stats are kept. */
val USER_MIGRATION_1_2 = object : Migration(1, 2) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("CREATE TABLE IF NOT EXISTS `track_ratings` (`trackId` INTEGER NOT NULL, `rating` INTEGER NOT NULL, PRIMARY KEY(`trackId`))")
        db.execSQL(
            "CREATE TABLE IF NOT EXISTS `daily_track_stats` (`day` TEXT NOT NULL, `trackId` INTEGER NOT NULL, " +
                "`plays` INTEGER NOT NULL, `ms` INTEGER NOT NULL, PRIMARY KEY(`day`, `trackId`))"
        )
    }
}
