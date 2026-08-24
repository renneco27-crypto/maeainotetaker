package com.cortesnotetaker.app.audio

import android.content.Context
import android.net.Uri
import android.util.Log
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.ReceiveChannel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

class AudioPlaybackManager(private val context: Context) {
    private var exoPlayer: ExoPlayer? = null

    private val playbackState = MutableStateFlow(PlaybackState.Idle)
    val playbackStateFlow = playbackState.asStateFlow()

    private val positionChannel = Channel<Long>(Channel.UNLIMITED)
    val positionFlow: ReceiveChannel<Long> = positionChannel

    private var positionUpdateRunnable: Runnable? = null

    fun loadAudio(filePath: String) {
        release()
        exoPlayer = ExoPlayer.Builder(context).build()
        val mediaItem = MediaItem.fromUri(Uri.parse(filePath))
        exoPlayer?.setMediaItem(mediaItem)
        exoPlayer?.prepare()
        
        exoPlayer?.addListener(object : Player.Listener {
            override fun onPlaybackStateChanged(state: Int) {
                when (state) {
                    Player.STATE_IDLE -> playbackState.value = PlaybackState.Idle
                    Player.STATE_BUFFERING -> playbackState.value = PlaybackState.Buffering
                    Player.STATE_READY -> playbackState.value = PlaybackState.Ready
                    Player.STATE_ENDED -> playbackState.value = PlaybackState.Ended
                }
            }

            override fun onPlayerError(error: androidx.media3.common.PlaybackException) {
                Log.e("AudioPlayback", "Playback error: ${error.message}")
                playbackState.value = PlaybackState.Error(error.message ?: "Unknown error")
            }
        })

        startPositionUpdates()
    }

    fun play() {
        exoPlayer?.playWhenReady = true
    }

    fun pause() {
        exoPlayer?.playWhenReady = false
    }

    fun seekTo(positionMs: Long) {
        exoPlayer?.seekTo(positionMs)
    }

    fun getCurrentPosition(): Long = exoPlayer?.currentPosition ?: 0L

    fun getDuration(): Long = exoPlayer?.duration ?: 0L

    fun isPlaying(): Boolean = exoPlayer?.playWhenReady == true && exoPlayer?.playbackState == Player.STATE_READY

    fun release() {
        stopPositionUpdates()
        exoPlayer?.release()
        exoPlayer = null
        playbackState.value = PlaybackState.Idle
    }

    private fun startPositionUpdates() {
        positionUpdateRunnable = Runnable {
            while (exoPlayer != null) {
                val position = exoPlayer?.currentPosition ?: 0L
                positionChannel.trySend(position)
                try {
                    Thread.sleep(200)
                } catch (e: InterruptedException) {
                    break
                }
            }
        }
        Thread(positionUpdateRunnable!!).start()
    }

    private fun stopPositionUpdates() {
        positionUpdateRunnable = null
    }

    sealed interface PlaybackState {
        data class Idle : PlaybackState
        data class Buffering : PlaybackState
        data class Ready : PlaybackState
        data class Ended : PlaybackState
        data class Error(val message: String) : PlaybackState
    }
}