package com.doublesymmetry.kotlinaudio.players

import android.content.Context
import android.media.AudioManager
import android.media.audiofx.Equalizer
import androidx.annotation.CallSuper
import androidx.core.content.ContextCompat
import androidx.media.AudioAttributesCompat
import androidx.media.AudioFocusRequestCompat
import androidx.media.AudioManagerCompat
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.ForwardingPlayer
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Metadata
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.Player.Listener
import androidx.media3.common.TrackSelectionParameters
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.cache.SimpleCache
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.session.legacy.RatingCompat
import com.doublesymmetry.kotlinaudio.event.PlayerEventHolder
import com.doublesymmetry.kotlinaudio.models.AudioItem
import com.doublesymmetry.kotlinaudio.models.AudioItemTransitionReason
import com.doublesymmetry.kotlinaudio.models.AudioPlayerState
import com.doublesymmetry.kotlinaudio.models.MediaSessionCallback
import com.doublesymmetry.kotlinaudio.models.PlayWhenReadyChangeData
import com.doublesymmetry.kotlinaudio.models.PlaybackError
import com.doublesymmetry.kotlinaudio.models.PlayerOptions
import com.doublesymmetry.kotlinaudio.models.PositionChangedReason
import com.doublesymmetry.kotlinaudio.models.setWakeMode
import com.doublesymmetry.kotlinaudio.players.components.Cache
import com.doublesymmetry.kotlinaudio.players.components.MediaFactory
import com.doublesymmetry.kotlinaudio.players.components.setupBuffer
import kotlinx.coroutines.MainScope
import timber.log.Timber
import java.util.Locale
import java.util.concurrent.TimeUnit

abstract class BaseAudioPlayer internal constructor(
    private val context: Context,
    val options: PlayerOptions = PlayerOptions()
) {

    val exoPlayer: ExoPlayer
    val forwardingPlayer: InnerForwardingPlayer
    val player: Player
        get() {
            return options.interceptPlayerActionsTriggeredExternally
                .takeIf { it }
                ?.let { forwardingPlayer }
                ?: exoPlayer
        }
    private var playerListener = InnerPlayerListener()
    private val scope = MainScope()
    private var cache: SimpleCache? = null
    val playerEventHolder = PlayerEventHolder()

    private var wasDucking = false
    private val focusManager: FocusManager = FocusManager()

    var alwaysPauseOnInterruption: Boolean
        get() = options.alwaysPauseOnInterruption
        set(v) { options.alwaysPauseOnInterruption = v }

    open val currentItem: AudioItem?
        get() = exoPlayer.currentMediaItem?.let { AudioItem.fromMediaItem(it) }

    private var _equalizer: Equalizer? = null
    open val equalizer: Equalizer
        get() {
            if (_equalizer == null) {
                _equalizer = Equalizer(0, exoPlayer.audioSessionId)
            }
            return _equalizer!!
        }

    var playbackError: PlaybackError? = null
    var playerState: AudioPlayerState = AudioPlayerState.IDLE
        private set(value) {
            if (value != field) {
                field = value
                playerEventHolder.updateAudioPlayerState(value)
                if (!options.handleAudioFocus) {
                    when (value) {
                        AudioPlayerState.IDLE,
                        AudioPlayerState.ERROR -> focusManager.abandonAudioFocusIfHeld()
                        AudioPlayerState.READY -> focusManager.requestAudioFocus()
                        else -> {}
                    }
                }
            }
        }

    var playWhenReady: Boolean
        get() = exoPlayer.playWhenReady
        set(value) {
            exoPlayer.playWhenReady = value
        }

    val duration: Long
        get() {
            return if (exoPlayer.duration == C.TIME_UNSET) 0
            else exoPlayer.duration
        }

    val isCurrentMediaItemLive: Boolean
        get() = exoPlayer.isCurrentMediaItemLive

    private var oldPosition = 0L

    val position: Long
        get() {
            return if (exoPlayer.currentPosition == C.INDEX_UNSET.toLong()) 0
            else exoPlayer.currentPosition
        }

    val bufferedPosition: Long
        get() {
            return if (exoPlayer.bufferedPosition == C.INDEX_UNSET.toLong()) 0
            else exoPlayer.bufferedPosition
        }

    private var volumeMultiplier = 1f
        private set(value) {
            field = value
            volume = volume
        }

    var volume: Float
        get() = exoPlayer.volume
        set(value) {
            exoPlayer.volume = value * volumeMultiplier
        }

    var playbackSpeed: Float
        get() = exoPlayer.playbackParameters.speed
        set(value) {
            exoPlayer.setPlaybackSpeed(value)
        }

    val isPlaying
        get() = exoPlayer.isPlaying

    var ratingType: Int = RatingCompat.RATING_NONE

    fun setAudioOffload(offload: Boolean = true) {
        val audioOffloadPreferences =
            TrackSelectionParameters.AudioOffloadPreferences.Builder()
                .setAudioOffloadMode(
                    if (offload) TrackSelectionParameters.AudioOffloadPreferences.AUDIO_OFFLOAD_MODE_ENABLED
                    else TrackSelectionParameters.AudioOffloadPreferences.AUDIO_OFFLOAD_MODE_DISABLED)
                // Add additional options as needed
                .setIsGaplessSupportRequired(true)
                .setIsSpeedChangeSupportRequired(true)
                .build()
        exoPlayer.trackSelectionParameters =
            exoPlayer.trackSelectionParameters
                .buildUpon()
                .setAudioOffloadPreferences(audioOffloadPreferences)
                .build()
    }

    init {
        if (options.cacheSize > 0) {
            cache = Cache.initCache(context, options.cacheSize)
        }
        playerEventHolder.updateAudioPlayerState(AudioPlayerState.IDLE)

        val renderer = DefaultRenderersFactory(context)
        renderer.setExtensionRendererMode(DefaultRenderersFactory.EXTENSION_RENDERER_MODE_PREFER)
        exoPlayer = ExoPlayer
            .Builder(context)
            .setRenderersFactory(renderer)
            .setHandleAudioBecomingNoisy(options.handleAudioBecomingNoisy)
            .setMediaSourceFactory(MediaFactory(context, cache))
            .setWakeMode(setWakeMode(options.wakeMode))
            .apply {
                setLoadControl(setupBuffer(options.bufferOptions))
            }
            .setSkipSilenceEnabled(options.skipSilence)
            .setName("kotlin-audio-player")
            .build()

        val audioAttributes = AudioAttributes.Builder()
            .setUsage(C.USAGE_MEDIA)
            .setContentType(options.audioContentType)
            .build()
        exoPlayer.setAudioAttributes(audioAttributes, options.handleAudioFocus)
        forwardingPlayer = InnerForwardingPlayer(exoPlayer)
        player.addListener(playerListener)
    }

    /**
     * Will replace the current item with a new one and load it into the player.
     * @param item The [AudioItem] to replace the current one.
     * @param playWhenReady Whether playback starts automatically.
     */
    open fun load(item: AudioItem, playWhenReady: Boolean = true) {
        exoPlayer.playWhenReady = playWhenReady
        load(item)
    }

    /**
     * Will replace the current item with a new one and load it into the player.
     * @param item The [AudioItem] to replace the current one.
     */
    open fun load(item: AudioItem) {
        exoPlayer.addMediaItem(item.toMediaItem())
        exoPlayer.prepare()
    }

    fun togglePlaying() {
        if (exoPlayer.isPlaying) {
            pause()
        } else {
            play()
        }
    }

    var skipSilence: Boolean
        get() = exoPlayer.skipSilenceEnabled
        set(value) {
            exoPlayer.skipSilenceEnabled = value
        }

    fun play() {
        exoPlayer.play()
        if (currentItem != null) {
            exoPlayer.prepare()
        }
    }

    fun prepare() {
        if (currentItem != null) {
            exoPlayer.prepare()
        }
    }

    fun pause() {
        exoPlayer.pause()
    }

    /**
     * Stops playback, without clearing the active item. Calling this method will cause the playback
     * state to transition to AudioPlayerState.IDLE and the player will release the loaded media and
     * resources required for playback.
     */
    @CallSuper
    open fun stop() {
        playerState = AudioPlayerState.STOPPED
        exoPlayer.playWhenReady = false
        exoPlayer.stop()
    }

    @CallSuper
    open fun clear() {
        exoPlayer.clearMediaItems()
    }

    /**
     * Pause playback whenever an item plays to its end.
     */
    fun setPauseAtEndOfItem(pause: Boolean) {
        exoPlayer.pauseAtEndOfMediaItems = pause
    }

    /**
     * Stops and destroys the player. Only call this when you are finished using the player, otherwise use [pause].
     */
    @CallSuper
    open fun destroy() {
        focusManager.abandonAudioFocusIfHeld()
        stop()
        player.removeListener(playerListener)
        exoPlayer.release()
        cache?.release()
        cache = null
    }

    open fun seek(duration: Long, unit: TimeUnit) {
        val positionMs = TimeUnit.MILLISECONDS.convert(duration, unit)
        exoPlayer.seekTo(positionMs)
    }

    open fun seekBy(offset: Long, unit: TimeUnit) {
        val positionMs = exoPlayer.currentPosition + TimeUnit.MILLISECONDS.convert(offset, unit)
        exoPlayer.seekTo(positionMs)
    }

    open fun setEqualizerLevels(levels: ShortArray): Boolean {
        var changed = false
        if (levels.size != equalizer.numberOfBands.toInt()) {
            throw Error("Invalid number of bands.")
        }
        for (i in 0 until equalizer.numberOfBands) {
            val band = i.toShort()
            val level = levels[i]
            val currentLevel = equalizer.getBandLevel(band)
            if (currentLevel != level) {
                equalizer.setBandLevel(band, levels[i])
                changed = true
            }
        }
        if (changed) {
            currentCustomPresetName = null  // Clear custom preset name when manually adjusting
        }
        equalizer.enabled = true
        return changed
    }

    open fun getEqualizerPresets(): Array<String> {
        val number = equalizer.numberOfPresets.toInt()
        val systemPresets = Array<String>(number) {""}
        for (i in 0 until number) {
            systemPresets[i] = equalizer.getPresetName(i.toShort())
        }

        // Combine system presets with custom presets
        val customPresetNames = customEQPresets.map { it.name }.toTypedArray()
        return systemPresets + customPresetNames
    }

    open fun getCurrentEqualizerPresetName(): String? {
        // First check if a custom preset is active
        if (currentCustomPresetName != null) {
            return currentCustomPresetName
        }
        
        // Otherwise check system preset
        val currentPreset = equalizer.currentPreset
        if (currentPreset >= 0 && currentPreset < equalizer.numberOfPresets) {
            return equalizer.getPresetName(currentPreset)
        }
        
        return null  // No preset active (manual adjustment)
    }

    open fun setEqualizerPreset(presetName: String): Boolean {
        // First check if it's a custom preset
        val customPreset = customEQPresets.find { it.name == presetName }
        if (customPreset != null) {
            val result = applyCustomEQPreset(customPreset)
            if (result) {
                currentCustomPresetName = presetName
            }
            return result
        }
        
        // Otherwise, check system presets
        val currentPreset = equalizer.currentPreset
        for (i in 0 until equalizer.numberOfPresets.toInt()) {
            val currentPresetName = equalizer.getPresetName(i.toShort())
            if (currentPresetName == presetName) {
                val newPreset = i.toShort()
                if (currentPreset != newPreset) {
                    equalizer.usePreset(newPreset)
                    currentCustomPresetName = null  // Clear custom preset name
                    return true
                }
                break
            }
        }
        return false
    }

    open fun setEqualizerEnabled(enabled: Boolean): Boolean {
        val changed = enabled != equalizer.enabled
        equalizer.enabled = enabled
        return changed
    }

    open fun clearEqualizer() {
        var equalizer = _equalizer ?: return
        equalizer.release()
        _equalizer = null
    }

    // Custom equalizer presets
    private data class EQPresetValue(val frequency: Int, val gain: Float)
    private data class EQPreset(val name: String, val values: List<EQPresetValue>)

    // Track current custom preset name
    private var currentCustomPresetName: String? = null
    
    private val customEQPresets = listOf(
        EQPreset("soft", listOf(
            EQPresetValue(500, 4.0f),
            EQPresetValue(1000, -4.0f),
            EQPresetValue(2000, -3.0f),
            EQPresetValue(4000, 4.0f),
            EQPresetValue(8000, -4.0f)
        )),
        EQPreset("relax", listOf(
            EQPresetValue(500, 3.0f),
            EQPresetValue(1000, -3.0f),
            EQPresetValue(2000, -6.5f),
            EQPresetValue(4000, -3.5f),
            EQPresetValue(8000, 5.0f)
        )),
        EQPreset("balance", listOf(
            EQPresetValue(500, 0.0f),
            EQPresetValue(1000, -2.0f),
            EQPresetValue(2000, 4.0f),
            EQPresetValue(4000, -2.5f),
            EQPresetValue(8000, -3.0f)
        )),
        EQPreset("whisper", listOf(
            EQPresetValue(500, 4.0f),
            EQPresetValue(1000, -6.5f),
            EQPresetValue(2000, 3.5f),
            EQPresetValue(4000, -4.0f),
            EQPresetValue(8000, 4.0f)
        )),
        EQPreset("focus", listOf(
            EQPresetValue(500, 8.0f),
            EQPresetValue(1000, 0.0f),
            EQPresetValue(2000, -12.0f),
            EQPresetValue(4000, -11.0f),
            EQPresetValue(8000, 8.0f)
        )),
        EQPreset("clear", listOf(
            EQPresetValue(500, -5.0f),
            EQPresetValue(1000, 5.0f),
            EQPresetValue(2000, 0.0f),
            EQPresetValue(4000, 2.0f),
            EQPresetValue(8000, 8.0f)
        ))
    )

    private fun applyCustomEQPreset(preset: EQPreset): Boolean {
        try {
            val numBands = equalizer.numberOfBands.toInt()
            val minLevel = equalizer.bandLevelRange[0]
            val maxLevel = equalizer.bandLevelRange[1]

            // Map preset frequencies to equalizer bands
            for (presetValue in preset.values) {
                var closestBand: Short = -1
                var closestDiff = Int.MAX_VALUE

                // Find the closest band to the target frequency
                for (band in 0 until numBands) {
                    val centerFreq = equalizer.getCenterFreq(band.toShort()) / 1000 // Convert to Hz
                    val diff = kotlin.math.abs(centerFreq - presetValue.frequency)
                    if (diff < closestDiff) {
                        closestDiff = diff
                        closestBand = band.toShort()
                    }
                }

                if (closestBand >= 0) {
                    // Convert dB to millibels (1 dB = 100 millibels)
                    val levelMillibels = (presetValue.gain * 100).toInt().toShort()
                    // Clamp to valid range
                    val clampedLevel = levelMillibels.coerceIn(minLevel, maxLevel)
                    equalizer.setBandLevel(closestBand, clampedLevel)
                }
            }

            equalizer.enabled = true
            return true
        } catch (e: Exception) {
            Timber.e(e, "Failed to apply custom EQ preset: ${preset.name}")
            return false
        }
    }
    
    @UnstableApi
    inner class InnerPlayerListener : Listener {

        /**
         * Called when there is metadata associated with the current playback time.
         */
        override fun onMetadata(metadata: Metadata) {
            playerEventHolder.updateOnTimedMetadata(metadata)
        }

        override fun onMediaMetadataChanged(mediaMetadata: MediaMetadata) {
            playerEventHolder.updateOnCommonMetadata(mediaMetadata)
        }

        /**
         * A position discontinuity occurs when the playing period changes, the playback position
         * jumps within the period currently being played, or when the playing period has been
         * skipped or removed.
         */
        override fun onPositionDiscontinuity(
            oldPosition: Player.PositionInfo,
            newPosition: Player.PositionInfo,
            reason: Int
        ) {
            this@BaseAudioPlayer.oldPosition = oldPosition.positionMs

            when (reason) {
                Player.DISCONTINUITY_REASON_AUTO_TRANSITION -> playerEventHolder.updatePositionChangedReason(
                    PositionChangedReason.AUTO(oldPosition.positionMs, newPosition.positionMs)
                )
                Player.DISCONTINUITY_REASON_SEEK -> playerEventHolder.updatePositionChangedReason(
                    PositionChangedReason.SEEK(oldPosition.positionMs, newPosition.positionMs)
                )
                Player.DISCONTINUITY_REASON_SEEK_ADJUSTMENT -> playerEventHolder.updatePositionChangedReason(
                    PositionChangedReason.SEEK_FAILED(
                        oldPosition.positionMs,
                        newPosition.positionMs
                    )
                )
                Player.DISCONTINUITY_REASON_REMOVE -> playerEventHolder.updatePositionChangedReason(
                    PositionChangedReason.QUEUE_CHANGED(
                        oldPosition.positionMs,
                        newPosition.positionMs
                    )
                )
                Player.DISCONTINUITY_REASON_SKIP -> playerEventHolder.updatePositionChangedReason(
                    PositionChangedReason.SKIPPED_PERIOD(
                        oldPosition.positionMs,
                        newPosition.positionMs
                    )
                )
                Player.DISCONTINUITY_REASON_INTERNAL -> playerEventHolder.updatePositionChangedReason(
                    PositionChangedReason.UNKNOWN(oldPosition.positionMs, newPosition.positionMs)
                )

                Player.DISCONTINUITY_REASON_SILENCE_SKIP -> playerEventHolder.updatePositionChangedReason(
                    PositionChangedReason.UNKNOWN(oldPosition.positionMs, newPosition.positionMs)
                )
            }
        }

        /**
         * Called when playback transitions to a media item or starts repeating a media item
         * according to the current repeat mode. Note that this callback is also called when the
         * playlist becomes non-empty or empty as a consequence of a playlist change.
         */
        override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
            when (reason) {
                Player.MEDIA_ITEM_TRANSITION_REASON_AUTO -> playerEventHolder.updateAudioItemTransition(
                    AudioItemTransitionReason.AUTO(oldPosition)
                )
                Player.MEDIA_ITEM_TRANSITION_REASON_PLAYLIST_CHANGED -> playerEventHolder.updateAudioItemTransition(
                    AudioItemTransitionReason.QUEUE_CHANGED(oldPosition)
                )
                Player.MEDIA_ITEM_TRANSITION_REASON_REPEAT -> playerEventHolder.updateAudioItemTransition(
                    AudioItemTransitionReason.REPEAT(oldPosition)
                )
                Player.MEDIA_ITEM_TRANSITION_REASON_SEEK -> playerEventHolder.updateAudioItemTransition(
                    AudioItemTransitionReason.SEEK_TO_ANOTHER_AUDIO_ITEM(oldPosition)
                )
            }
        }

        /**
         * Called when the value returned from Player.getPlayWhenReady() changes.
         */
        override fun onPlayWhenReadyChanged(playWhenReady: Boolean, reason: Int) {
            val pausedBecauseReachedEnd = reason == Player.PLAY_WHEN_READY_CHANGE_REASON_END_OF_MEDIA_ITEM
            playerEventHolder.updatePlayWhenReadyChange(PlayWhenReadyChangeData(playWhenReady, pausedBecauseReachedEnd))
        }

        /**
         * The generic onEvents callback provides access to the Player object and specifies the set
         * of events that occurred together. It’s always called after the callbacks that correspond
         * to the individual events.
         */
        override fun onEvents(player: Player, events: Player.Events) {
            // Note that it is necessary to set `playerState` in order, since each mutation fires an
            // event.
            for (i in 0 until events.size()) {
                when (events[i]) {
                    Player.EVENT_PLAYBACK_STATE_CHANGED -> {
                        val state = when (player.playbackState) {
                            Player.STATE_BUFFERING -> AudioPlayerState.BUFFERING
                            Player.STATE_READY -> AudioPlayerState.READY
                            Player.STATE_IDLE ->
                                // Avoid transitioning to idle from error or stopped
                                if (
                                    playerState == AudioPlayerState.ERROR ||
                                    playerState == AudioPlayerState.STOPPED
                                )
                                    null
                                else
                                    AudioPlayerState.IDLE
                            Player.STATE_ENDED ->
                                if (player.mediaItemCount > 0) AudioPlayerState.ENDED
                                else AudioPlayerState.IDLE
                            else -> null // noop
                        }
                        if (state != null && state != playerState) {
                            playerState = state
                        }
                    }
                    Player.EVENT_MEDIA_ITEM_TRANSITION -> {
                        playbackError = null
                        if (currentItem != null) {
                            playerState = AudioPlayerState.LOADING
                            if (isPlaying) {
                                playerState = AudioPlayerState.READY
                                playerState = AudioPlayerState.PLAYING
                            }
                        }
                    }
                    Player.EVENT_PLAY_WHEN_READY_CHANGED -> {
                        if (!player.playWhenReady && playerState != AudioPlayerState.STOPPED) {
                            playerState = AudioPlayerState.PAUSED
                        }
                    }
                    Player.EVENT_IS_PLAYING_CHANGED -> {
                        if (player.isPlaying) {
                            playerState = AudioPlayerState.PLAYING
                        }
                    }
                }
            }
        }

        override fun onPlayerError(error: PlaybackException) {
            val _playbackError = PlaybackError(
                error.errorCodeName
                    .replace("ERROR_CODE_", "")
                    .lowercase(Locale.getDefault())
                    .replace("_", "-"),
                error.message
            )
            playerEventHolder.updatePlaybackError(_playbackError)
            playbackError = _playbackError
            playerState = AudioPlayerState.ERROR
        }
    }

    @UnstableApi
    inner class InnerForwardingPlayer(player: ExoPlayer): ForwardingPlayer(player) {
        override fun setMediaItems(mediaItems: MutableList<MediaItem>, resetPosition: Boolean) {
            // override setMediaItem handling to RNTP
            return
        }

        override fun addMediaItems(mediaItems: MutableList<MediaItem>) {
            // override setMediaItem handling to RNTP
            return
        }

        override fun addMediaItems(index: Int, mediaItems: MutableList<MediaItem>) {
            // override setMediaItem handling to RNTP
            return
        }

        override fun setMediaItems(
            mediaItems: MutableList<MediaItem>,
            startIndex: Int,
            startPositionMs: Long
        ) {
            // override setMediaItem handling to RNTP
            return
        }

        override fun setMediaItems(mediaItems: MutableList<MediaItem>) {
            // override setMediaItem handling to RNTP
            return
        }

        override fun play() {
            playerEventHolder.updateOnPlayerActionTriggeredExternally(MediaSessionCallback.PLAY)
        }

        override fun pause() {
            playerEventHolder.updateOnPlayerActionTriggeredExternally(MediaSessionCallback.PAUSE)
        }

        override fun seekToNext() {
            playerEventHolder.updateOnPlayerActionTriggeredExternally(MediaSessionCallback.NEXT)
        }

        override fun seekToNextMediaItem() {
            playerEventHolder.updateOnPlayerActionTriggeredExternally(MediaSessionCallback.NEXT)
        }

        override fun seekToPrevious() {
            playerEventHolder.updateOnPlayerActionTriggeredExternally(MediaSessionCallback.PREVIOUS)
        }

        override fun seekToPreviousMediaItem() {
            playerEventHolder.updateOnPlayerActionTriggeredExternally(MediaSessionCallback.PREVIOUS)
        }

        override fun seekForward() {
            playerEventHolder.updateOnPlayerActionTriggeredExternally(MediaSessionCallback.FORWARD)
        }

        override fun seekBack() {
            playerEventHolder.updateOnPlayerActionTriggeredExternally(MediaSessionCallback.REWIND)
        }

        override fun stop() {
            playerEventHolder.updateOnPlayerActionTriggeredExternally(MediaSessionCallback.STOP)
        }

        override fun seekTo(mediaItemIndex: Int, positionMs: Long) {
            playerEventHolder.updateOnPlayerActionTriggeredExternally(
                MediaSessionCallback.SEEK(
                    positionMs
                )
            )
        }

        override fun seekTo(positionMs: Long) {
            playerEventHolder.updateOnPlayerActionTriggeredExternally(
                MediaSessionCallback.SEEK(
                    positionMs
                )
            )
        }
    }

    inner class FocusManager() {
        private var hasAudioFocus = false
        private var focus: AudioFocusRequestCompat? = null

        fun requestAudioFocus() {
            if (hasAudioFocus) return

            val manager = ContextCompat.getSystemService(context, AudioManager::class.java)

            focus = AudioFocusRequestCompat.Builder(AudioManagerCompat.AUDIOFOCUS_GAIN)
                .setOnAudioFocusChangeListener(
                    { focusChange ->
                        Timber.d("Audio focus changed")
                        val isPermanent = focusChange == AudioManager.AUDIOFOCUS_LOSS
                        val isPaused = when (focusChange) {
                            AudioManager.AUDIOFOCUS_LOSS, AudioManager.AUDIOFOCUS_LOSS_TRANSIENT -> true
                            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK -> options.alwaysPauseOnInterruption
                            else -> false
                        }
                        if (!options.handleAudioFocus) {
                            if (isPermanent) focusManager.abandonAudioFocusIfHeld()

                            val isDucking = focusChange == AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK
                                    && !options.alwaysPauseOnInterruption
                            if (isDucking) {
                                volumeMultiplier = 0.5f
                                wasDucking = true
                            } else if (wasDucking) {
                                volumeMultiplier = 1f
                                wasDucking = false
                            }
                        }
                        playerEventHolder.updateOnAudioFocusChanged(isPaused, isPermanent)
                    }
                )
                .setAudioAttributes(
                    AudioAttributesCompat.Builder()
                        .setUsage(AudioAttributesCompat.USAGE_MEDIA)
                        .setContentType(AudioAttributesCompat.CONTENT_TYPE_MUSIC)
                        .build()
                )
                .setWillPauseWhenDucked(options.alwaysPauseOnInterruption)
                .build()

            val result: Int = if (manager != null && focus != null) {
                AudioManagerCompat.requestAudioFocus(manager, focus!!)
            } else {
                AudioManager.AUDIOFOCUS_REQUEST_FAILED
            }

            hasAudioFocus = (result == AudioManager.AUDIOFOCUS_REQUEST_GRANTED)
        }

        fun abandonAudioFocusIfHeld() {
            if (!hasAudioFocus) return

            val manager = ContextCompat.getSystemService(context, AudioManager::class.java)

            val result: Int = if (manager != null && focus != null) {
                AudioManagerCompat.abandonAudioFocusRequest(manager, focus!!)
            } else {
                AudioManager.AUDIOFOCUS_REQUEST_FAILED
            }

            hasAudioFocus = (result != AudioManager.AUDIOFOCUS_REQUEST_GRANTED)
        }
    }
}
