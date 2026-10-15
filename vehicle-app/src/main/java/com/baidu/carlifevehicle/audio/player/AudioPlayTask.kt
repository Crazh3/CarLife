package com.baidu.carlifevehicle.audio.player

import android.media.AudioAttributes
import android.media.AudioAttributes.*
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import android.os.Build
import android.util.Log
import com.baidu.carlife.protobuf.CarlifeMusicInitProto
import com.baidu.carlife.sdk.*
import com.baidu.carlife.sdk.Configs.CONFIG_SAVE_AUDIO_FILE
import com.baidu.carlife.sdk.Constants.MSG_CHANNEL_AUDIO
import com.baidu.carlife.sdk.Constants.MSG_CHANNEL_AUDIO_TTS
import com.baidu.carlife.sdk.Constants.MSG_CHANNEL_AUDIO_VR
import com.baidu.carlife.sdk.internal.audio.AudioFocusManager
import com.baidu.carlifevehicle.audio.player.source.AudioParams
import com.baidu.carlifevehicle.audio.player.source.AudioSource
import com.baidu.carlifevehicle.audio.player.source.ResizableArray
import com.baidu.carlife.sdk.internal.protocol.CarLifeMessage
import com.baidu.carlife.sdk.internal.protocol.ServiceTypes
import com.baidu.carlife.sdk.util.Logger
import com.baidu.carlife.sdk.util.formatISO8601
import java.io.File
import java.io.FileOutputStream
import java.io.OutputStream
import java.util.*
import java.util.concurrent.ExecutorService
import java.util.concurrent.Future
import java.util.concurrent.Semaphore
import java.util.concurrent.atomic.AtomicBoolean

class AudioPlayTask(
    private val context: CarLifeContext,
    private val channel: Int,
    private val isReceiver: Boolean,
    val source: AudioSource,
    private val callbacks: AudioPlayer.Callbacks
) : Runnable, ConnectionChangeListener, AudioManager.OnAudioFocusChangeListener {
    companion object {
        const val FOCUS_DELAYED = 99
    }

    // 通道相关协议指令
    private val serviceMediaInit: Int
    private val serviceMediaPause: Int
    private val serviceMediaResume: Int
    private val serviceMediaStop: Int
    private val serviceMediaData: Int

    private val semaphore = Semaphore(1)
    private var future: Future<*>? = null

    var audioTrack: AudioTrack? = null

    private val isStopped = AtomicBoolean(false)

    private var isMediaNeedInit = AtomicBoolean(true)

    @Volatile
    var state: Int = AudioPlayer.STATE_IDLE
        private set(value) {
            field = value
            callbacks.onStateChanged(source, value)
        }

    // 当前焦点状态
    private var focusState: Int = AudioManager.AUDIOFOCUS_LOSS

    // 如果暂停时处于联机状态，发送了media pause消息，如果中间未发生过重连
    // 当resume时，需要发送media resume消息
    private var needSendMediaResume = false

    private var audioOutput: OutputStream? = null

    init {
        context.registerConnectionChangeListener(this)
        Logger.d(Constants.TAG, "AudioPlayTask init ", source)

        if (context.getConfig(CONFIG_SAVE_AUDIO_FILE, false)) {
            val dir = File(context.cacheDir, "AudioPlayer")
            if (!dir.exists()) {
                dir.mkdirs()
            }
            audioOutput = FileOutputStream(File(dir, Date().formatISO8601() + ".pcm"))
        }

        when (channel) {
            MSG_CHANNEL_AUDIO -> {
                serviceMediaInit = ServiceTypes.MSG_MEDIA_INIT
                serviceMediaPause = ServiceTypes.MSG_MEDIA_PAUSE
                serviceMediaResume = ServiceTypes.MSG_MEDIA_RESUME_PLAY
                serviceMediaStop = ServiceTypes.MSG_MEDIA_STOP
                serviceMediaData = ServiceTypes.MSG_MEDIA_DATA
            }
            MSG_CHANNEL_AUDIO_TTS -> {
                serviceMediaInit = ServiceTypes.MSG_NAV_TTS_INIT
                serviceMediaStop = ServiceTypes.MSG_NAV_TTS_END
                serviceMediaData = ServiceTypes.MSG_NAV_TTS_DATA
                // TTS通道无法暂停，继续播放
                serviceMediaPause = -1
                serviceMediaResume = -1
            }
            else -> {
                serviceMediaInit = ServiceTypes.MSG_VR_AUDIO_INIT
                serviceMediaPause = ServiceTypes.MSG_VR_AUDIO_INTERRUPT
                serviceMediaStop = ServiceTypes.MSG_VR_AUDIO_STOP
                serviceMediaData = ServiceTypes.MSG_VR_AUDIO_DATA
                // VR通道没有resume
                serviceMediaResume = -1
            }
        }
    }

    /**
     * 处理焦点变换相关逻辑
     */
    override fun onAudioFocusChange(focusChange: Int) {
        if (isStopped.get()) {
            // release 之后，不再处理焦点变化
            return
        }

        val newState = when (focusChange) {
            AudioManager.AUDIOFOCUS_GAIN,
            AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK,
            AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_EXCLUSIVE,
            AudioManager.AUDIOFOCUS_GAIN_TRANSIENT -> AudioManager.AUDIOFOCUS_GAIN
            else -> focusChange
        }

        if (newState == AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK) {
            setVolume(0.2f)
        } else {
            setVolume(1.0f)
        }

        if (focusState == FOCUS_DELAYED) {
            if (newState == AudioManager.AUDIOFOCUS_GAIN && state != AudioPlayer.STATE_PAUSED) {
                // 获取到了焦点，释放信号，开始播放
                // 如果当前是pause状态，无需释放信号量，否则会导致异常播放
                semaphore.release()
            } else if (newState == AudioManager.AUDIOFOCUS_LOSS) {
                callbacks.onError(
                    source,
                    AudioPlayer.ERROR_REQUEST_FOCUS_FAILED,
                    "request focus failed"
                )
                stop()
            }
        } else if (focusState == AudioManager.AUDIOFOCUS_GAIN) {
            if (newState == AudioManager.AUDIOFOCUS_LOSS) {
                stop()
            } else if (newState == AudioManager.AUDIOFOCUS_LOSS_TRANSIENT) {
                pause()
            }
        } else if (focusState == AudioManager.AUDIOFOCUS_LOSS_TRANSIENT) {
            if (newState == AudioManager.AUDIOFOCUS_GAIN) {
                resume()
            } else if (newState == AudioManager.AUDIOFOCUS_LOSS) {
                stop()
            }
        }
        focusState = newState
    }

    override fun onConnectionDetached(context: CarLifeContext) {
        // 如果发生了断连，resume时将不需要再发送media resume，清空标记
        needSendMediaResume = false
        isMediaNeedInit.set(true)
    }

    @Synchronized
    fun execute(executor: ExecutorService) {
        if (isStopped.get()) {
            callbacks.onError(
                source,
                AudioPlayer.ERROR_CLOSED_SOURCE,
                "can not play a closed audio source"
            )
            return
        }

        if (state != AudioPlayer.STATE_IDLE) {
            callbacks.onError(
                source,
                AudioPlayer.ERROR_REPLAY_SOURCE,
                "can not play a played audio source"
            )
            return
        }

        if (source.focusType != AudioManager.AUDIOFOCUS_NONE) {
            // 需要PlayTask管理焦点
            val focusRequest = context.requestAudioFocus(
                this,
                source.streamType,
                source.focusType,
                source.allowFocusDelayed,
                source.forceGrantFocus
            )
            if (focusRequest == AudioFocusManager.AUDIOFOCUS_REQUEST_DELAYED) {
                // 如果焦点被delay，获取锁，禁止播放
                focusState =
                    FOCUS_DELAYED
                semaphore.acquire()
            } else if (focusRequest == AudioFocusManager.AUDIOFOCUS_REQUEST_FAILED) {
                callbacks.onError(
                    source,
                    AudioPlayer.ERROR_REQUEST_FOCUS_FAILED,
                    "request focus failed"
                )
                focusState = AudioManager.AUDIOFOCUS_LOSS
                return
            }
            focusState = source.focusType
        }

        callbacks.onAudioChanged(source)
        state = AudioPlayer.STATE_LOADING
        future = executor.submit(this)
    }

    @Synchronized
    fun setVolume(gain: Float) {

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            audioTrack?.setVolume(gain)
        } else {
            audioTrack?.setStereoVolume(gain, gain)
        }
    }

    @Synchronized
    fun pause() {
        // loading和playing状态才允许暂停
        if (state == AudioPlayer.STATE_PLAYING || state == AudioPlayer.STATE_LOADING) {
            state = AudioPlayer.STATE_PAUSED
            if (focusState != FOCUS_DELAYED) {
                audioTrack?.pause()
                semaphore.acquire()
            }
            // 恢复音量
            setVolume(1.0f)
        }
    }

    @Synchronized
    fun resume() {
        // pause 状态才允许继续播放
        if (state == AudioPlayer.STATE_PAUSED) {
            state = AudioPlayer.STATE_LOADING
            if (focusState != FOCUS_DELAYED) {
                audioTrack?.play()
                semaphore.release()
                needSendMediaResume = false
            }
            // 恢复音量
            setVolume(1.0f)
        }
    }

    fun stop() {
        // stop 保证只能调用一次
        if (!isStopped.getAndSet(true)) {
            state = AudioPlayer.STATE_STOPPED
            future?.cancel(true)
            source.close()

            isMediaNeedInit.set(true)

            audioOutput?.close()

            if (source.focusType != AudioManager.AUDIOFOCUS_NONE) {
                context.abandonAudioFocus(this)
            }

            context.unregisterConnectionChangeListener(this)
            Logger.d(Constants.TAG, "AudioPlayTask stop ", source)
        }
    }

    override fun run() {
        if (isStopped.get()) {
            // 说明被执行之前就被stop了
            return
        }

        try {
            val params = source.params()
            if (params.useStaticMode) {
                // 对于1秒之内的音频并且未连接的情况下，使用STATIC模式播放
                staticPlay(params, source)
            } else {
                streamPlay(params, source)
            }

        } catch (e: InterruptedException) {
            Log.i(Constants.TAG, "AudioPlayTask interrupted by player")
            callbacks.onFinish(source, false)
        } catch (e: Exception) {
            if (state == AudioPlayer.STATE_PAUSED) {
                Log.i(Constants.TAG, "AudioPlayTask paused by player")
            } else {
                Logger.e(Constants.TAG, "AudioPlayTask play exception: " + Log.getStackTraceString(e))
                callbacks.onError(source, AudioPlayer.ERROR_EXCEPTION, e.toString())
            }

        }

        stop()
    }

    private fun staticPlay(params: AudioParams, source: AudioSource) {
        val buffer = ResizableArray(params.totalBytes)
        try {
            val readSize = source.drainTo(buffer, 3000L)
            audioTrack = createAudioTrack(params, AudioTrack.MODE_STATIC)
            audioTrack?.write(buffer.array, 0, buffer.size)
            audioTrack?.play()

            Thread.sleep(params.duration)
            callbacks.onFinish(source, true)
        } finally {
            releaseAudioTrack()
        }
    }

    private fun streamPlay(params: AudioParams, source: AudioSource) {
        // 两帧音频的数据量

        val buffer = ResizableArray(8 * 1024)
        val remotePlay = AtomicBoolean(false)
        val positionTracker = AudioTrackPositionTracker(params)
        var lastPosition = 0L
        var isComplete = false

        try {
            while (!isStopped.get()) {
                semaphore.acquire()
                semaphore.release()

                buffer.clear()
                val readSize = source.readFrame(buffer, 0, 3000)
                if (readSize > 0) {
                    audioOutput?.write(buffer.array, 0, readSize)
                } else if (readSize == -1) {
                    // 播放完成
                    isComplete = true
                }

                if (readSize == 0) {
                    // 超时未读到数据，notify
                    synchronized(this) {
                        if (state == AudioPlayer.STATE_PLAYING) {
                            state = AudioPlayer.STATE_LOADING
                        }
                    }
                } else if (readSize > 0) {

                    // 发送或播放音乐
                    synchronized(this) {
                        if (state == AudioPlayer.STATE_LOADING) {
                            state = AudioPlayer.STATE_PLAYING
                        }
                    }

                    // 同步时间，校准步调
                    if (!isReceiver) {
                        positionTracker.sync()
                    }

                    if (remotePlay.get()) {
                        if (isMediaNeedInit.getAndSet(false)) {
                            // send media init message
                            sendMediaInit(params)
                        }
                        sendMediaData(buffer.array, 0, readSize)
                    } else {
                        if (audioTrack == null) {
                            audioTrack =
                                createAudioTrack(params, AudioTrack.MODE_STREAM).apply { play() }
                        }
                        audioTrack?.write(buffer.array, 0, readSize)
                    }

                    // 更新时间点
                    if (!isReceiver) {
                        positionTracker.track(readSize)
                    }

                    Logger.v(Constants.TAG, "AudioPlayTask streamPlay readSize $readSize")
                } else {
                    // 重置
                    if (!isReceiver) {
                        positionTracker.sync(0)
                    }
                    break
                }

                val currentPosition = source.currentPosition()
                if (currentPosition - lastPosition > 500) {
                    callbacks.onProgress(source, currentPosition, params.duration)
                    lastPosition = currentPosition
                }
            }
        } catch (e: Exception) {
            if (!source.isClosed()) {
                // 如果是主动关闭导致的exception，忽略掉
                throw e
            }
        } finally {
            callbacks.onFinish(source, isComplete)
            releaseAudioTrack()
        }
    }

    @Synchronized
    private fun releaseAudioTrack() {
        audioTrack?.release()
        audioTrack = null
    }

    private fun sendMediaInit(params: AudioParams) {
        val message = CarLifeMessage.obtain(channel, serviceMediaInit)
        message.payload(
            CarlifeMusicInitProto.CarlifeMusicInit.newBuilder()
                .setSampleRate(params.sampleRate)
                .setChannelConfig(params.channelCount)
                .setSampleFormat(params.bitDepth)
                .build()
        )
        context.postMessage(message)
    }

    private fun sendMediaPause() {
        if (serviceMediaPause > 0) {
            context.postMessage(channel, serviceMediaPause)
        }
    }

    private fun sendMediaResume() {
        if (serviceMediaResume > 0) {
            context.postMessage(channel, serviceMediaResume)
        }
    }

    private fun sendMediaStop() {
        if (serviceMediaStop > 0) {
            context.postMessage(channel, serviceMediaStop)
        }
    }

    private fun sendMediaData(buffer: ByteArray, offset: Int, length: Int) {
        val message = CarLifeMessage.obtain(channel, serviceMediaData)
        message.payload(buffer, offset, length)
        context.postMessage(message)
    }

    private fun createAudioTrack(params: AudioParams, mode: Int): AudioTrack {
        val bufferSize =
            if (mode == AudioTrack.MODE_STATIC)
                params.totalBytes
            else AudioTrack.getMinBufferSize(
                params.sampleRate,
                params.channelConfig,
                params.audioFormat
            )

        return if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) {
            AudioTrack(
                AudioManager.STREAM_MUSIC,
                params.sampleRate,
                params.channelConfig,
                params.audioFormat,
                bufferSize,
                mode
            )
        } else {
            AudioTrack.Builder()
                .setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(getUsage())
                        .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                        .build()
                )
                .setAudioFormat(
                    AudioFormat.Builder()
                        .setEncoding(params.audioFormat)
                        .setSampleRate(params.sampleRate)
                        .setChannelMask(params.channelConfig)
                        .build()
                )
                .setTransferMode(mode)
                .setBufferSizeInBytes(bufferSize)
                .build()
        }
    }

    private fun getUsage() = when (channel) {
        MSG_CHANNEL_AUDIO_VR, MSG_CHANNEL_AUDIO -> USAGE_MEDIA
        MSG_CHANNEL_AUDIO_TTS -> USAGE_ASSISTANCE_NAVIGATION_GUIDANCE
        else -> USAGE_MEDIA
    }
}