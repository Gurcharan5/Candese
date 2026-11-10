package com.cadence.player.data

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

enum class RgMode { OFF, TRACK, ALBUM }

enum class SortField(val label: String) {
    TITLE("Title"),
    ARTIST("Artist"),
    ALBUM("Album"),
    DATE_MODIFIED("Date modified"),
    DURATION("Duration"),
    YEAR("Year"),
}

class Settings(context: Context) {
    private val prefs = context.getSharedPreferences("settings", Context.MODE_PRIVATE)

    private val _crossfade = MutableStateFlow(prefs.getInt("crossfade", 0))
    private val _rgMode = MutableStateFlow(
        runCatching { RgMode.valueOf(prefs.getString("rgMode", "TRACK")!!) }.getOrDefault(RgMode.TRACK)
    )
    private val _preamp = MutableStateFlow(prefs.getFloat("preamp", 0f))
    private val _preventClipping = MutableStateFlow(prefs.getBoolean("preventClipping", true))
    private val _sortField = MutableStateFlow(
        runCatching { SortField.valueOf(prefs.getString("sortField", "TITLE")!!) }.getOrDefault(SortField.TITLE)
    )
    private val _sortDescending = MutableStateFlow(prefs.getBoolean("sortDescending", false))
    private val _splitArtists = MutableStateFlow(prefs.getBoolean("splitArtists", true))
    private val _skipFadeMs = MutableStateFlow(prefs.getInt("skipFadeMs", 500))
    private val _analyzeUntagged = MutableStateFlow(prefs.getBoolean("analyzeUntagged", true))

    /** 0 = off (gapless playback). */
    val crossfadeSeconds: StateFlow<Int> = _crossfade
    val rgMode: StateFlow<RgMode> = _rgMode
    val preampDb: StateFlow<Float> = _preamp
    val preventClipping: StateFlow<Boolean> = _preventClipping
    val songSort: StateFlow<SortField> = _sortField
    val songSortDescending: StateFlow<Boolean> = _sortDescending

    /** Treat "A, B" in the artist tag as two artists. */
    val splitArtists: StateFlow<Boolean> = _splitArtists

    /** Length of the short crossfade used when you skip a track by hand (0 = off). */
    val skipFadeMs: StateFlow<Int> = _skipFadeMs

    /** Measure loudness of songs that have no ReplayGain tags. */
    val analyzeUntagged: StateFlow<Boolean> = _analyzeUntagged

    fun setSplitArtists(on: Boolean) {
        _splitArtists.value = on
        prefs.edit().putBoolean("splitArtists", on).apply()
    }

    fun setSkipFadeMs(ms: Int) {
        _skipFadeMs.value = ms
        prefs.edit().putInt("skipFadeMs", ms).apply()
    }

    fun setAnalyzeUntagged(on: Boolean) {
        _analyzeUntagged.value = on
        prefs.edit().putBoolean("analyzeUntagged", on).apply()
    }

    fun setSongSort(field: SortField, descending: Boolean) {
        _sortField.value = field
        _sortDescending.value = descending
        prefs.edit().putString("sortField", field.name).putBoolean("sortDescending", descending).apply()
    }

    fun setCrossfade(seconds: Int) {
        _crossfade.value = seconds
        prefs.edit().putInt("crossfade", seconds).apply()
    }

    fun setRgMode(mode: RgMode) {
        _rgMode.value = mode
        prefs.edit().putString("rgMode", mode.name).apply()
    }

    fun setPreamp(db: Float) {
        _preamp.value = db
        prefs.edit().putFloat("preamp", db).apply()
    }

    fun setPreventClipping(on: Boolean) {
        _preventClipping.value = on
        prefs.edit().putBoolean("preventClipping", on).apply()
    }
}
