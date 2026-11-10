package com.cadence.player.playback

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.util.Size
import androidx.media3.common.util.BitmapLoader
import androidx.media3.common.util.UnstableApi
import com.google.common.util.concurrent.ListenableFuture
import com.google.common.util.concurrent.MoreExecutors
import java.util.concurrent.Callable
import java.util.concurrent.Executors

/**
 * Supplies cover art to the media session, which feeds the notification, lock screen, smartwatches,
 * Bluetooth displays and Android Auto. Songs carry their own file URI as the artwork URI, and
 * ContentResolver.loadThumbnail() reads the embedded or folder artwork straight from that file.
 */
@UnstableApi
class ThumbnailBitmapLoader(private val context: Context) : BitmapLoader {
    private val executor = MoreExecutors.listeningDecorator(Executors.newFixedThreadPool(2))

    override fun supportsMimeType(mimeType: String): Boolean = true

    override fun decodeBitmap(data: ByteArray): ListenableFuture<Bitmap> =
        executor.submit(Callable {
            BitmapFactory.decodeByteArray(data, 0, data.size) ?: throw IllegalStateException("Undecodable artwork")
        })

    override fun loadBitmap(uri: Uri): ListenableFuture<Bitmap> =
        executor.submit(Callable { load(uri) })

    private fun load(uri: Uri): Bitmap {
        try {
            return context.contentResolver.loadThumbnail(uri, Size(512, 512), null)
        } catch (_: Exception) {
            // not an audio file URI, or it has no artwork: fall through to a plain image decode
        }
        context.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it) }?.let { return it }
        throw IllegalStateException("No artwork for $uri")
    }
}
