package com.cadence.player.playback

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import androidx.core.content.ContextCompat
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.SimpleBasePlayer
import androidx.media3.common.SimpleBasePlayer.MediaItemData
import androidx.media3.common.SimpleBasePlayer.PositionSupplier
import androidx.media3.common.SimpleBasePlayer.State
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.audio.AudioSink
import androidx.media3.exoplayer.audio.DefaultAudioSink
import com.cadence.player.data.RgMode
import com.cadence.player.data.Settings
import com.cadence.player.data.TrackStatDelta
import com.cadence.player.data.UserDao
import com.google.common.util.concurrent.ListenableFuture
import com.google.common.util.concurrent.Futures
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import java.time.LocalDate
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin

private val COMMANDS: Player.Commands = Player.Commands.Builder().addAll(
    Player.COMMAND_PLAY_PAUSE,
    Player.COMMAND_PREPARE,
    Player.COMMAND_STOP,
    Player.COMMAND_RELEASE,
    Player.COMMAND_SEEK_IN_CURRENT_MEDIA_ITEM,
    Player.COMMAND_SEEK_TO_NEXT_MEDIA_ITEM,
    Player.COMMAND_SEEK_TO_NEXT,
    Player.COMMAND_SEEK_TO_PREVIOUS_MEDIA_ITEM,
    Player.COMMAND_SEEK_TO_PREVIOUS,
    Player.COMMAND_SEEK_TO_MEDIA_ITEM,
    Player.COMMAND_GET_CURRENT_MEDIA_ITEM,
    Player.COMMAND_GET_TIMELINE,
    Player.COMMAND_GET_METADATA,
    Player.COMMAND_SET_REPEAT_MODE,
    Player.COMMAND_SET_MEDIA_ITEM,
    Player.COMMAND_CHANGE_MEDIA_ITEMS,
).build()

/**
 * A single [Player] backed by two ExoPlayer "decks".
 *
 *  - Crossfade OFF  -> the active deck holds the whole queue. ExoPlayer's own playlist handling gives
 *                      sample-accurate gapless playback (encoder delay/padding is trimmed for MP3/AAC/etc.).
 *  - Crossfade ON   -> each deck plays one track. Near the end of the current track the next one is
 *                      preloaded on the idle deck, then both are volume-ramped with an equal-power curve.
 *
 * ReplayGain is applied per deck by [ReplayGainAudioProcessor] inside the audio sink.
 * Everything is exposed through [SimpleBasePlayer], so MediaSession, the notification and Compose just see a Player.
 */
@OptIn(UnstableApi::class)
class CadencePlayer(
    private val context: Context,
    private val settings: Settings,
    private val userDao: UserDao,
) : SimpleBasePlayer(Looper.getMainLooper()) {

    private class QueueItem(val mediaItem: MediaItem) {
        val durationMs = mediaItem.durationMs
        val gain = mediaItem.gainInfo
    }

    // ---- decks -------------------------------------------------------------------------------------------

    private inner class Deck(val id: Int) {
        val processor = ReplayGainAudioProcessor()
        var baseVolume = 1f

        val exo: ExoPlayer = ExoPlayer.Builder(context, object : DefaultRenderersFactory(context) {
            override fun buildAudioSink(
                context: Context,
                enableFloatOutput: Boolean,
                enableAudioTrackPlaybackParams: Boolean,
            ): AudioSink = DefaultAudioSink.Builder(context)
                .setEnableFloatOutput(enableFloatOutput)
                .setEnableAudioTrackPlaybackParams(enableAudioTrackPlaybackParams)
                .setAudioProcessors(arrayOf<AudioProcessor>(processor))
                .build()
        })
            // Audio focus is handled once, by CadencePlayer. Two decks must not fight each other for focus.
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(C.USAGE_MEDIA)
                    .setContentType(C.AUDIO_CONTENT_TYPE_MUSIC)
                    .build(),
                /* handleAudioFocus = */ false
            )
            .setWakeMode(C.WAKE_MODE_LOCAL)
            .build()

        init {
            exo.addListener(object : Player.Listener {
                override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
                    if (id == active && !reordering) statNewPlay = true
                    if (id == active && !builtForFade && !reordering && mediaItem != null) {
                        index = exo.currentMediaItemIndex
                        processor.setCursor(index)
                        updateAppliedGain()
                        invalidateState()
                    }
                }

                override fun onPlaybackStateChanged(playbackState: Int) {
                    if (id != active) return
                    if (playbackState == Player.STATE_READY) errorStreak = 0
                    if (playbackState == Player.STATE_ENDED && builtForFade) advanceHard() else invalidateState()
                }

                override fun onIsPlayingChanged(isPlaying: Boolean) {
                    if (id == active) invalidateState()
                }

                override fun onPlayerError(error: PlaybackException) {
                    if (id == active) handleError(error)
                }
            })
        }

        fun applyVolume() {
            exo.volume = (baseVolume * duck).coerceIn(0f, 1f)
        }
    }

    // ---- state -------------------------------------------------------------------------------------------

    private val decks = arrayOf(Deck(0), Deck(1))
    private var active = 0
    private val deck get() = decks[active]
    private val other get() = decks[1 - active]

    private var items: List<QueueItem> = emptyList()
    private var playlist: List<MediaItemData> = emptyList()
    private var index = 0
    private var wantPlay = false
    private var pwrReason = Player.PLAY_WHEN_READY_CHANGE_REASON_USER_REQUEST
    private var repeat = Player.REPEAT_MODE_OFF
    private var duck = 1f
    private var errorStreak = 0

    /** Queue order before shuffle was switched on (null while shuffle is off). */
    private var originalItems: List<QueueItem>? = null
    private var reordering = false
    private val _shuffle = MutableStateFlow(false)
    /** True when the queue has been shuffled. Shuffling is a real Fisher-Yates permutation, see [SecureShuffle]. */
    val shuffleEnabled: StateFlow<Boolean> = _shuffle

    /** True when the current queue was loaded in crossfade (one-track-per-deck) mode. */
    private var builtForFade = false
    private val fadeMode get() = settings.crossfadeSeconds.value > 0

    private var preparedNext: Int? = null
    private var fading = false
    private var fadingOut: Deck? = null
    private var fadeStart = 0L
    private var fadeLen = 1L

    private val handler = Handler(Looper.getMainLooper())
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    private val _appliedGainDb = MutableStateFlow<Float?>(null)
    /** ReplayGain currently applied to the playing track, in dB (null when ReplayGain is off). */
    val appliedGainDb: StateFlow<Float?> = _appliedGainDb

    // ---- audio focus / noisy -----------------------------------------------------------------------------

    private val audioManager = context.getSystemService(AudioManager::class.java)
    private var resumeOnFocus = false

    private val focusRequest: AudioFocusRequest = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
        .setAudioAttributes(
            android.media.AudioAttributes.Builder()
                .setUsage(android.media.AudioAttributes.USAGE_MEDIA)
                .setContentType(android.media.AudioAttributes.CONTENT_TYPE_MUSIC)
                .build()
        )
        .setOnAudioFocusChangeListener({ change ->
            when (change) {
                AudioManager.AUDIOFOCUS_LOSS -> {
                    resumeOnFocus = false
                    setPlay(false, Player.PLAY_WHEN_READY_CHANGE_REASON_AUDIO_FOCUS_LOSS)
                }
                AudioManager.AUDIOFOCUS_LOSS_TRANSIENT -> {
                    resumeOnFocus = wantPlay
                    setPlay(false, Player.PLAY_WHEN_READY_CHANGE_REASON_AUDIO_FOCUS_LOSS)
                }
                AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK -> {
                    duck = 0.25f
                    applyVolumes()
                }
                AudioManager.AUDIOFOCUS_GAIN -> {
                    duck = 1f
                    applyVolumes()
                    if (resumeOnFocus) {
                        resumeOnFocus = false
                        setPlay(true, Player.PLAY_WHEN_READY_CHANGE_REASON_AUDIO_FOCUS_LOSS)
                    }
                }
            }
        }, handler)
        .build()

    private val noisyReceiver = object : BroadcastReceiver() {
        override fun onReceive(c: Context?, i: Intent?) {
            if (wantPlay) setPlay(false, Player.PLAY_WHEN_READY_CHANGE_REASON_AUDIO_BECOMING_NOISY)
        }
    }

    init {
        ContextCompat.registerReceiver(
            context, noisyReceiver, IntentFilter(AudioManager.ACTION_AUDIO_BECOMING_NOISY),
            ContextCompat.RECEIVER_NOT_EXPORTED
        )
        scope.launch {
            combine(
                settings.crossfadeSeconds, settings.rgMode, settings.preampDb, settings.preventClipping
            ) { _, _, _, _ -> }.collect { onSettingsChanged() }
        }
    }

    // ---- SimpleBasePlayer --------------------------------------------------------------------------------

    override fun getState(): State {
        val pos = if (items.isEmpty()) 0L else deck.exo.currentPosition.coerceAtLeast(0L)
        val exoState = deck.exo.playbackState
        val moving = wantPlay && exoState == Player.STATE_READY
        return State.Builder()
            .setAvailableCommands(COMMANDS)
            .setPlayWhenReady(wantPlay, pwrReason)
            .setPlaybackState(if (items.isEmpty()) Player.STATE_IDLE else exoState)
            .setRepeatMode(repeat)
            .setPlaylist(playlist)
            .setCurrentMediaItemIndex(index)
            .setContentPositionMs(
                if (moving) PositionSupplier.getExtrapolating(pos, 1f) else PositionSupplier.getConstant(pos)
            )
            .build()
    }

    override fun handleSetPlayWhenReady(playWhenReady: Boolean): ListenableFuture<*> {
        setPlay(playWhenReady, Player.PLAY_WHEN_READY_CHANGE_REASON_USER_REQUEST)
        return Futures.immediateVoidFuture()
    }

    override fun handlePrepare(): ListenableFuture<*> {
        if (items.isNotEmpty() && deck.exo.playbackState == Player.STATE_IDLE) {
            loadCurrent(deck.exo.currentPosition)
        }
        return Futures.immediateVoidFuture()
    }

    override fun handleStop(): ListenableFuture<*> {
        finishFade()
        wantPlay = false
        handler.removeCallbacks(tick)
        handler.removeCallbacks(statsTick)
        flushStats()
        decks.forEach { it.exo.stop() }
        preparedNext = null
        invalidateState()
        return Futures.immediateVoidFuture()
    }

    override fun handleRelease(): ListenableFuture<*> {
        flushStats()
        handler.removeCallbacksAndMessages(null)
        scope.cancel()
        runCatching { context.unregisterReceiver(noisyReceiver) }
        audioManager.abandonAudioFocusRequest(focusRequest)
        decks.forEach { it.exo.release() }
        return Futures.immediateVoidFuture()
    }

    override fun handleSetRepeatMode(repeatMode: Int): ListenableFuture<*> {
        repeat = repeatMode
        if (!builtForFade) deck.exo.repeatMode = repeatMode
        discardPreload()
        return Futures.immediateVoidFuture()
    }

    override fun handleSetMediaItems(
        mediaItems: List<MediaItem>,
        startIndex: Int,
        startPositionMs: Long,
    ): ListenableFuture<*> {
        finishFade()
        discardPreload()
        originalItems = null
        _shuffle.value = false
        items = mediaItems.map { QueueItem(it) }
        rebuildPlaylist()
        index = if (items.isEmpty()) 0 else (if (startIndex == C.INDEX_UNSET) 0 else startIndex).coerceIn(0, items.lastIndex)
        if (items.isEmpty()) {
            decks.forEach { it.exo.stop(); it.exo.clearMediaItems() }
            updateAppliedGain()
        } else {
            loadCurrent(if (startPositionMs == C.TIME_UNSET) 0L else startPositionMs)
        }
        return Futures.immediateVoidFuture()
    }

    override fun handleAddMediaItems(index: Int, mediaItems: List<MediaItem>): ListenableFuture<*> {
        val at = index.coerceIn(0, items.size)
        val added = mediaItems.map { QueueItem(it) }
        val wasEmpty = items.isEmpty()
        items = items.toMutableList().apply { addAll(at, added) }
        originalItems = originalItems?.plus(added) // keep un-shuffling consistent
        rebuildPlaylist()
        if (wasEmpty) {
            this.index = 0
            loadCurrent(0L)
        } else {
            if (at <= this.index) this.index += added.size
            if (builtForFade) {
                discardPreload() // the preloaded "next" track may no longer be next
            } else {
                deck.exo.addMediaItems(at, mediaItems)
                setSequence(deck, this.index)
            }
        }
        return Futures.immediateVoidFuture()
    }

    override fun handleRemoveMediaItems(fromIndex: Int, toIndex: Int): ListenableFuture<*> {
        val to = toIndex.coerceAtMost(items.size)
        if (fromIndex < 0 || fromIndex >= to) return Futures.immediateVoidFuture()
        val count = to - fromIndex
        val removed = items.subList(fromIndex, to).toSet()
        val currentRemoved = index in fromIndex until to
        val newItems = items.filterIndexed { i, _ -> i < fromIndex || i >= to }
        originalItems = originalItems?.filter { it !in removed }

        if (newItems.isEmpty()) {
            finishFade()
            preparedNext = null
            handler.removeCallbacks(tick)
            handler.removeCallbacks(statsTick)
            flushStats()
            decks.forEach { it.exo.stop(); it.exo.clearMediaItems() }
            items = emptyList()
            rebuildPlaylist()
            index = 0
            wantPlay = false
            updateAppliedGain()
            return Futures.immediateVoidFuture()
        }

        if (builtForFade) {
            discardPreload()
            items = newItems
            rebuildPlaylist()
            if (currentRemoved) {
                finishFade()
                index = fromIndex.coerceAtMost(newItems.lastIndex)
                loadCurrent(0L)
                if (fromIndex > newItems.lastIndex) setPlay(false, Player.PLAY_WHEN_READY_CHANGE_REASON_USER_REQUEST)
            } else if (fromIndex < index) {
                index -= count
            }
        } else {
            reordering = true
            try {
                deck.exo.removeMediaItems(fromIndex, to)
            } finally {
                reordering = false
            }
            items = newItems
            rebuildPlaylist()
            index = deck.exo.currentMediaItemIndex.coerceIn(0, newItems.lastIndex)
            if (currentRemoved) statNewPlay = true
            setSequence(deck, index)
        }
        updateAppliedGain()
        return Futures.immediateVoidFuture()
    }

    override fun handleSeek(mediaItemIndex: Int, positionMs: Long, seekCommand: Int): ListenableFuture<*> {
        if (items.isEmpty()) return Futures.immediateVoidFuture()
        val pos = if (positionMs == C.TIME_UNSET) 0L else positionMs
        if (mediaItemIndex == index) {
            finishFade()
            deck.exo.seekTo(pos)
        } else {
            jumpTo(mediaItemIndex.coerceIn(0, items.lastIndex), pos)
        }
        invalidateState()
        return Futures.immediateVoidFuture()
    }

    // ---- engine ------------------------------------------------------------------------------------------

    private fun rebuildPlaylist() {
        playlist = items.mapIndexed { i, q ->
            MediaItemData.Builder("${q.mediaItem.mediaId}#$i").apply {
                setMediaItem(q.mediaItem)
                if (q.durationMs > 0) setDurationUs(q.durationMs * 1000)
            }.build()
        }
    }

    private fun setPlay(play: Boolean, reason: Int) {
        if (play) {
            val granted = audioManager.requestAudioFocus(focusRequest) == AudioManager.AUDIOFOCUS_REQUEST_GRANTED
            if (!granted) {
                wantPlay = false
                invalidateState()
                return
            }
        }
        wantPlay = play
        pwrReason = reason
        if (play && items.isNotEmpty() && deck.exo.playbackState == Player.STATE_ENDED) {
            jumpTo(0, 0L) // restart queue after it finished
        }
        deck.exo.playWhenReady = play
        fadingOut?.exo?.playWhenReady = play
        if (play) scheduleTick() else {
            handler.removeCallbacks(tick)
            handler.removeCallbacks(statsTick)
            flushStats()
        }
        if (!play && reason == Player.PLAY_WHEN_READY_CHANGE_REASON_USER_REQUEST) {
            audioManager.abandonAudioFocusRequest(focusRequest)
        }
        invalidateState()
    }

    /** (Re)loads the queue on the active deck for the current mode and index. */
    private fun loadCurrent(positionMs: Long) {
        if (!fading) {
            other.exo.stop(); other.exo.clearMediaItems()
        }
        preparedNext = null
        builtForFade = fadeMode
        statNewPlay = true
        val d = deck
        d.baseVolume = 1f
        d.applyVolume()
        if (builtForFade) {
            d.exo.repeatMode = Player.REPEAT_MODE_OFF
            d.exo.setMediaItem(items[index].mediaItem, positionMs.coerceAtLeast(0L))
        } else {
            d.exo.repeatMode = repeat
            d.exo.setMediaItems(items.map { it.mediaItem }, index, positionMs.coerceAtLeast(0L))
        }
        setSequence(d, index)
        d.exo.playWhenReady = wantPlay
        d.exo.prepare()
        updateAppliedGain()
        scheduleTick()
        invalidateState()
    }

    private fun jumpTo(i: Int, positionMs: Long) {
        finishFade()
        discardPreload()
        index = i
        if (builtForFade) {
            loadCurrent(positionMs)
        } else {
            statNewPlay = true
            deck.exo.seekTo(i, positionMs)
            deck.processor.setCursor(i)
            if (deck.exo.playbackState == Player.STATE_IDLE) deck.exo.prepare()
            deck.exo.playWhenReady = wantPlay
            updateAppliedGain()
        }
    }

    private fun nextIndex(): Int? = when {
        items.isEmpty() -> null
        repeat == Player.REPEAT_MODE_ONE -> index
        index + 1 < items.size -> index + 1
        repeat == Player.REPEAT_MODE_ALL -> 0
        else -> null
    }

    private fun advanceHard() {
        val n = nextIndex()
        if (n == null) {
            invalidateState()
            return
        }
        finishFade()
        discardPreload()
        index = n
        loadCurrent(0L)
    }

    private fun handleError(error: PlaybackException) {
        Log.w("CadencePlayer", "Playback error", error)
        errorStreak++
        if (errorStreak > 5) {
            setPlay(false, Player.PLAY_WHEN_READY_CHANGE_REASON_USER_REQUEST)
            return
        }
        val n = nextIndex()
        if (n != null && n != index) jumpTo(n, 0L) else invalidateState()
    }

    // ---- listening stats ---------------------------------------------------------------------------------

    private val ioScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val pendingMs = HashMap<Long, Long>()
    private val pendingPlays = HashMap<Long, Int>()
    private var pendingTotal = 0L
    private var lastStatSample = 0L
    private var lastFlush = 0L
    private var statTrackId = -1L
    private var statListened = 0L
    private var statCounted = false
    private var statNewPlay = true

    private val statsTick = object : Runnable {
        override fun run() {
            sampleStats()
            if (wantPlay && items.isNotEmpty()) handler.postDelayed(this, 1000L)
        }
    }

    /** Adds the time actually spent playing since the last sample. A play counts after 30 s (or half a short track). */
    private fun sampleStats() {
        val now = SystemClock.elapsedRealtime()
        val delta = (now - lastStatSample).coerceIn(0L, 3000L)
        lastStatSample = now
        if (!wantPlay || items.isEmpty() || !deck.exo.isPlaying) return
        val queueItem = items[index]
        val id = queueItem.mediaItem.mediaId.toLongOrNull() ?: return
        if (statNewPlay || id != statTrackId) {
            statTrackId = id
            statListened = 0L
            statCounted = false
            statNewPlay = false
        }
        statListened += delta
        pendingTotal += delta
        pendingMs[id] = (pendingMs[id] ?: 0L) + delta
        val threshold = if (queueItem.durationMs > 0) min(30_000L, queueItem.durationMs / 2) else 30_000L
        if (!statCounted && statListened >= threshold) {
            statCounted = true
            pendingPlays[id] = (pendingPlays[id] ?: 0) + 1
        }
        if (now - lastFlush > 15_000L) flushStats()
    }

    private fun flushStats() {
        lastFlush = SystemClock.elapsedRealtime()
        if (pendingTotal <= 0L && pendingPlays.isEmpty()) return
        val day = LocalDate.now().toString()
        val total = pendingTotal
        val ids = HashSet<Long>(pendingMs.keys).apply { addAll(pendingPlays.keys) }
        val rows = ids.map { TrackStatDelta(it, pendingPlays[it] ?: 0, pendingMs[it] ?: 0L) }
        pendingMs.clear()
        pendingPlays.clear()
        pendingTotal = 0L
        val now = System.currentTimeMillis()
        ioScope.launch { runCatching { userDao.record(day, total, rows, now) } }
    }

    // ---- shuffle -----------------------------------------------------------------------------------------

    /**
     * Turns shuffle on or off without interrupting the current track.
     * On: the playing track stays first and every other track is put in a uniformly random order.
     * Off: the original order is restored and playback carries on from the current track.
     */
    fun setShuffle(enabled: Boolean) {
        if (enabled == _shuffle.value) return
        if (items.isEmpty()) {
            _shuffle.value = enabled
            return
        }
        val current = items[index]
        if (enabled) {
            originalItems = items
            val others = items.filter { it !== current }
            reorder(listOf(current) + SecureShuffle.shuffled(others), 0)
        } else {
            val original = originalItems ?: items
            originalItems = null
            reorder(original, original.indexOf(current).coerceAtLeast(0))
        }
        _shuffle.value = enabled
    }

    /** Swaps the queue order while the current item keeps playing. [newItems] must contain the current item. */
    private fun reorder(newItems: List<QueueItem>, newIndex: Int) {
        reordering = true
        try {
            if (builtForFade) {
                discardPreload()
            } else {
                val exo = deck.exo
                if (index < exo.mediaItemCount - 1) exo.removeMediaItems(index + 1, exo.mediaItemCount)
                if (index > 0) exo.removeMediaItems(0, index)
                exo.addMediaItems(0, newItems.subList(0, newIndex).map { it.mediaItem })
                exo.addMediaItems(exo.mediaItemCount, newItems.subList(newIndex + 1, newItems.size).map { it.mediaItem })
            }
        } finally {
            reordering = false
        }
        items = newItems
        index = newIndex
        rebuildPlaylist()
        setSequence(deck, index)
        updateAppliedGain()
        invalidateState()
    }

    // ---- gain --------------------------------------------------------------------------------------------

    private fun gainFor(i: Int): Float = ReplayGain.linearGain(
        items.getOrNull(i)?.gain, settings.rgMode.value, settings.preampDb.value, settings.preventClipping.value
    )

    private fun setSequence(d: Deck, startIndex: Int) {
        if (builtForFade) {
            d.processor.setSequence(floatArrayOf(gainFor(startIndex)), 0)
        } else {
            d.processor.setSequence(FloatArray(items.size) { gainFor(it) }, startIndex)
        }
    }

    private fun updateAppliedGain() {
        _appliedGainDb.value =
            if (settings.rgMode.value == RgMode.OFF || items.isEmpty()) null else ReplayGain.toDb(gainFor(index))
    }

    private fun applyVolumes() {
        decks.forEach { it.applyVolume() }
    }

    private fun onSettingsChanged() {
        if (items.isEmpty()) {
            builtForFade = fadeMode
            updateAppliedGain()
            return
        }
        if (fadeMode != builtForFade) {
            // Switching between gapless and crossfade: rebuild at the current position.
            val pos = deck.exo.currentPosition
            finishFade()
            loadCurrent(pos)
        } else {
            setSequence(deck, index)
            preparedNext?.let { setSequence(other, it) }
            updateAppliedGain()
            invalidateState()
        }
    }

    // ---- crossfade ---------------------------------------------------------------------------------------

    private val tick = object : Runnable {
        override fun run() {
            onTick()
            if (items.isNotEmpty() && wantPlay) handler.postDelayed(this, if (fading) 40L else 200L)
        }
    }

    private fun scheduleTick() {
        handler.removeCallbacks(tick)
        handler.removeCallbacks(statsTick)
        if (wantPlay && items.isNotEmpty()) {
            handler.post(tick)
            lastStatSample = SystemClock.elapsedRealtime()
            handler.postDelayed(statsTick, 1000L)
        }
    }

    private fun onTick() {
        if (fading) {
            updateFade()
            return
        }
        if (!builtForFade || !fadeMode) return
        val d = deck.exo
        if (d.playbackState != Player.STATE_READY) return
        val next = nextIndex() ?: return
        val duration = if (d.duration != C.TIME_UNSET) d.duration else items[index].durationMs
        if (duration <= 0) return
        val remaining = duration - d.currentPosition
        val xf = min(settings.crossfadeSeconds.value * 1000L, duration / 2)

        if (preparedNext == null && remaining <= xf + 5000) prepareNext(next)
        if (preparedNext == next && other.exo.playbackState == Player.STATE_READY && remaining <= xf) {
            startFade(next, remaining)
        }
    }

    private fun prepareNext(next: Int) {
        val o = other
        o.baseVolume = 0f
        o.applyVolume()
        o.exo.repeatMode = Player.REPEAT_MODE_OFF
        o.exo.setMediaItem(items[next].mediaItem)
        o.exo.playWhenReady = false
        setSequence(o, next)
        o.exo.prepare()
        preparedNext = next
    }

    private fun startFade(next: Int, remainingMs: Long) {
        fadingOut = deck
        fading = true
        statNewPlay = true
        fadeStart = SystemClock.elapsedRealtime()
        fadeLen = remainingMs.coerceAtLeast(300L)
        active = 1 - active
        index = next
        preparedNext = null
        deck.exo.playWhenReady = true
        updateAppliedGain()
        invalidateState()
    }

    private fun updateFade() {
        val t = ((SystemClock.elapsedRealtime() - fadeStart).toFloat() / fadeLen).coerceIn(0f, 1f)
        // Equal-power curve keeps perceived loudness steady through the overlap.
        deck.baseVolume = sin(t * PI / 2).toFloat()
        deck.applyVolume()
        fadingOut?.let {
            it.baseVolume = cos(t * PI / 2).toFloat()
            it.applyVolume()
        }
        if (t >= 1f) finishFade()
    }

    private fun finishFade() {
        if (!fading) return
        fadingOut?.let {
            it.exo.stop()
            it.exo.clearMediaItems()
            it.baseVolume = 1f
            it.applyVolume()
        }
        fadingOut = null
        fading = false
        deck.baseVolume = 1f
        deck.applyVolume()
    }

    private fun discardPreload() {
        if (!fading && preparedNext != null) {
            other.exo.stop()
            other.exo.clearMediaItems()
        }
        preparedNext = null
    }
}
