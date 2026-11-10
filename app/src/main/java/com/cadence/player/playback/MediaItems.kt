package com.cadence.player.playback

import android.net.Uri
import android.os.Bundle
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import com.cadence.player.data.Track

private const val K_DURATION = "durationMs"
private const val K_TG = "rgTrackGain"
private const val K_AG = "rgAlbumGain"
private const val K_TP = "rgTrackPeak"
private const val K_AP = "rgAlbumPeak"

fun Track.toMediaItem(): MediaItem {
    val extras = Bundle().apply {
        putLong(K_DURATION, durationMs)
        rgTrackGain?.let { putFloat(K_TG, it) }
        rgAlbumGain?.let { putFloat(K_AG, it) }
        rgTrackPeak?.let { putFloat(K_TP, it) }
        rgAlbumPeak?.let { putFloat(K_AP, it) }
    }
    return MediaItem.Builder()
        .setMediaId(id.toString())
        .setUri(uri)
        .setMediaMetadata(
            MediaMetadata.Builder()
                .setTitle(title)
                .setArtist(artist)
                .setAlbumTitle(album)
                .setArtworkUri(Uri.parse(uri)) // ThumbnailBitmapLoader turns the song URI into its cover art
                .setIsPlayable(true)
                .setIsBrowsable(false)
                .setExtras(extras)
                .build()
        )
        .build()
}

val MediaItem.durationMs: Long
    get() = mediaMetadata.extras?.getLong(K_DURATION) ?: 0L

val MediaItem.gainInfo: GainInfo
    get() {
        val e = mediaMetadata.extras
        fun f(key: String): Float? = if (e != null && e.containsKey(key)) e.getFloat(key) else null
        return GainInfo(f(K_TG), f(K_AG), f(K_TP), f(K_AP))
    }
