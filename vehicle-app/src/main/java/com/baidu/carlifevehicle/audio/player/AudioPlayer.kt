package com.baidu.carlifevehicle.audio.player

import com.baidu.carlife.sdk.CarLifeContext
import com.baidu.carlife.sdk.Constants
import com.baidu.carlife.sdk.util.Logger
import com.baidu.carlifevehicle.audio.player.source.AudioSource
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

class AudioPlayer(
    private val context: CarLifeContext,
    private val channel: Int,
    private val isReceiver: Boolean,
    callbacks: Callbacks
) {
    companion object {
        const val STATE_IDLE = 0
        const val STATE_LOADING = 1
        const val STATE_PAUSED = 2
        const val STATE_PLAYING = 3
        const val STATE_STOPPED = 4

        const val ERROR_EXCEPTION = 1
        const val ERROR_REQUEST_FOCUS_FAILED = 2
        const val ERROR_CLOSED_SOURCE = 3
        const val ERROR_REPLAY_SOURCE = 4
    }

    interface Callbacks {
        fun onAudioChanged(source: AudioSource) {}

        fun onStateChanged(source: AudioSource, state: Int) {}

        fun onProgress(source: AudioSource, position: Long, duration: Long) {}

        fun onFinish(source: AudioSource, completed: Boolean) {}

        fun onError(source: AudioSource, errorCode: Int, errorMessage: String) {}

    }

    inner class CallbacksWrapper(val callbacks: Callbacks) :
        Callbacks {
        override fun onAudioChanged(source: AudioSource) {
            context.post {
                callbacks.onAudioChanged(source)
            }
        }

        override fun onStateChanged(source: AudioSource, state: Int) {
            context.post {
                callbacks.onStateChanged(source, state)
            }
        }

        override fun onProgress(source: AudioSource, position: Long, duration: Long) {
            context.post {
                callbacks.onProgress(source, position, duration)
            }
        }

        override fun onFinish(source: AudioSource, completed: Boolean) {
            if (playingTask?.source == source) {
                playingTask = null
            }
            context.post {
                Logger.d(Constants.TAG, "AudioPlayer ", channel, " onCompletion")
                callbacks.onFinish(source, completed)
            }
        }

        override fun onError(source: AudioSource, errorCode: Int, errorMessage: String) {
            if (playingTask?.source == source) {
                playingTask = null
            }
            context.post {
                Logger.d(Constants.TAG, "AudioPlayer ", channel, " onError")
                callbacks.onError(source, errorCode, errorMessage)
            }
        }

    }

    private val executor = Executors.newSingleThreadExecutor {
        return@newSingleThreadExecutor Thread(it).apply { name = "CarLife_AudioPlayer" }
    }
    private val isReleased = AtomicBoolean(false)
    private val callbacksWrapper = CallbacksWrapper(callbacks)

    private var playingTask: AudioPlayTask? = null

    @Volatile
    var state: Int = STATE_IDLE
        private set
        get() = playingTask?.state ?: STATE_IDLE

    fun play(source: AudioSource) {
        if (!isReleased.get()) {
            // 先暂停当前任务
            stop()

            playingTask =
                AudioPlayTask(
                    context,
                    channel,
                    isReceiver,
                    source,
                    callbacksWrapper
                )
            playingTask?.execute(executor)
            Logger.d(Constants.TAG, "AudioPlayer ", channel, " play")
        }
    }

    fun prepare(source: AudioSource) {
        play(source)
        // prepare 就是只加载不开始播放，使用resume触发播放
        pause()
    }

    fun setVolume(gain: Float) {
        Logger.d(Constants.TAG, "AudioPlayer ", channel, " setVolume ", gain)
        playingTask?.setVolume(gain)
    }

    fun duck() {
        setVolume(0.2f)
    }

    fun unduck() {
        setVolume(1f)
    }

    fun pause() {
        Logger.d(Constants.TAG, "AudioPlayer ", channel, " pause")
        playingTask?.pause()
    }

    fun resume() {
        Logger.d(Constants.TAG, "AudioPlayer ", channel, " resume")
        playingTask?.resume()
    }

    fun stop() {
        Logger.d(Constants.TAG, "AudioPlayer ", channel, " stop")
        playingTask?.let {
            it.stop()
            playingTask = null
        }
    }

    fun release() {
        if (!isReleased.getAndSet(true)) {
            // stop 当前播放任务如果有的话
            stop()
        }
    }
}