package com.cortesnotetaker.app.audio

import android.content.Context
import android.net.Uri
import android.util.Log
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

class AudioPlaybackManager(private val context: Context) {
    private var exoPlayer: ExoPlayer? = null
    private val scope = CoroutineScope(Dispatchers.Main)
    private var positionJob: Job? = null

    private val _playbackState = MutableStateFlow<PlaybackState>(PlaybackState.Idle)
    val playbackStateFlow: StateFlow<PlaybackState> = _playbackState.asStateFlow()

    private val _positionFlow = MutableStateFlow(0L)
    val positionFlow: StateFlow<Long> = _positionFlow.asStateFlow()

    fun loadAudio(filePath: String) {
        release()
        if (filePath.isBlank()) {
            Log.w("AudioPlayback", "Audio file path is empty")
            return
        }

        val file = java.io.File(filePath)
        if (!file.exists()) {
            Log.e("AudioPlayback", "Audio file does not exist: $filePath")
            return
        }

        exoPlayer = ExoPlayer.Builder(context).build().apply {
            val uri = Uri.fromFile(file)
            val mediaItem = MediaItem.fromUri(uri)
            setMediaItem(mediaItem)
            prepare()
            
            addListener(object : Player.Listener {
                override fun onPlaybackStateChanged(state: Int) {
                    when (state) {
                        Player.STATE_IDLE -> _playbackState.value = PlaybackState.Idle
                        Player.STATE_BUFFERING -> _playbackState.value = PlaybackState.Buffering
                        Player.STATE_READY -> _playbackState.value = PlaybackState.Ready
                        Player.STATE_ENDED -> {
                            _playbackState.value = PlaybackState.Ended
                            _positionFlow.value = duration.coerceAtLeast(0L)
                            stopPositionUpdates()
                        }
                    }
                }

                override fun onIsPlayingChanged(isPlaying: Boolean) {
                    if (isPlaying) {
                        startPositionUpdates()
                    } else {
                        stopPositionUpdates()
                    }
                }

                override fun onPlayerError(error: PlaybackException) {
                    Log.e("AudioPlayback", "Playback error: ${error.message}")
                    _playbackState.value = PlaybackState.Error(error.message ?: "Unknown error")
                }
            })
        }
    }

    fun play() {
        exoPlayer?.let { player ->
            if (player.playbackState == Player.STATE_ENDED) {
                player.seekTo(0)
            }
            player.play()
        }
        startPositionUpdates()
    }

    fun pause() {
        exoPlayer?.pause()
        stopPositionUpdates()
    }

    fun seekTo(positionMs: Long) {
        exoPlayer?.seekTo(positionMs)
        _positionFlow.value = positionMs
    }

    fun getCurrentPosition(): Long = exoPlayer?.currentPosition ?: 0L

    fun getDuration(): Long = exoPlayer?.duration ?: 0L

    fun isPlaying(): Boolean = exoPlayer?.isPlaying == true

    fun release() {
        stopPositionUpdates()
        exoPlayer?.release()
        exoPlayer = null
        _playbackState.value = PlaybackState.Idle
    }

    private fun startPositionUpdates() {
        positionJob?.cancel()
        positionJob = scope.launch {
            while (isActive) {
                exoPlayer?.let {
                    if (it.isPlaying) {
                        _positionFlow.value = it.currentPosition
                    }
                }
                delay(100)
            }
        }
    }

    private fun stopPositionUpdates() {
        positionJob?.cancel()
        positionJob = null
    }

    sealed interface PlaybackState {
        data object Idle : PlaybackState
        data object Buffering : PlaybackState
        data object Ready : PlaybackState
        data object Ended : PlaybackState
        data class Error(val message: String) : PlaybackState
    }
}